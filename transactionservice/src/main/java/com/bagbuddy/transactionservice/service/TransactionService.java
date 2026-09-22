package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.notification.TransactionStatusChanged;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Effect;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.StatusPair;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Transition;
import com.bagbuddy.transactionservice.web.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TripClient tripClient;
    private final TransactionStateMachine stateMachine;
    /** Ecriture programmatique : la phase d'ecriture de update() ne peut pas passer par le proxy. */
    private final TransactionTemplate writeTx;
    /**
     * Notifications : l'evenement est publie dans la transaction d'ecriture et traite apres son
     * commit (TransactionNotifier), jamais pour une ecriture annulee.
     */
    private final ApplicationEventPublisher events;
    /** Echecs de restitution de capacite : du poids pris sur une annonce que personne ne tient plus. */
    private final MeterRegistry meters;
    /** Remboursement et versement d'une transaction payee qui se termine ou s'annule. */
    private final SettlementService settlement;

    public TransactionService(TransactionRepository transactionRepository,
                              TripClient tripClient,
                              TransactionStateMachine stateMachine,
                              TransactionTemplate writeTx,
                              ApplicationEventPublisher events,
                              MeterRegistry meters,
                              SettlementService settlement) {
        this.transactionRepository = transactionRepository;
        this.tripClient = tripClient;
        this.stateMachine = stateMachine;
        this.writeTx = writeTx;
        this.events = events;
        this.meters = meters;
        this.settlement = settlement;
    }

    /**
     * When true (the default, and the only safe setting in production), a transaction may only
     * reach the confirmed state once Stripe has actually confirmed the payment through the
     * signed webhook. Set it to false for local development, where stripeservice is disabled
     * and paying is simulated.
     */
    @Value("${PAYMENTS_REQUIRE_STRIPE:true}")
    private boolean requireStripe;

    public List<Transaction> getByUser(String userId) {
        return transactionRepository.findByBuyerIdOrSellerIdOrderByCreatedAtDesc(userId, userId);
    }

    public List<Transaction> getBySeller(String sellerId) {
        return transactionRepository.findBySellerId(sellerId);
    }

    public List<Transaction> getByBuyer(String buyerId) {
        return transactionRepository.findByBuyerId(buyerId);
    }

    public Long countByUser(String userId) {
        return transactionRepository.countByBuyerIdOrSellerId(userId, userId);
    }

    public Double getTotalEarnedBySeller(String sellerId) {
        return transactionRepository.sumCompletedBySeller(sellerId).doubleValue();
    }

    public Double getTotalSpentByBuyer(String buyerId) {
        return transactionRepository.sumCompletedByBuyer(buyerId).doubleValue();
    }

    /** Participant-only read: a transaction carries both parties' contact details. */
    public Transaction getOne(Long id, String callerSub) {
        Transaction tx = findOrThrow(id);
        requireParticipant(tx, callerSub);
        return tx;
    }

    /**
     * Meme controle de participation que getOne, sans charger la transaction : la messagerie n'a
     * besoin que de savoir qui en est partie et ou elle en est. La regle reste ici, ou elle est
     * deja, plutot que recopiee chez l'appelant.
     *
     * @return la paire de statuts courante
     */
    @Transactional(readOnly = true)
    public StatusPair requireParticipantIn(Long id, String callerSub) {
        TransactionRepository.Participants parties = transactionRepository.findParticipants(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
        if (!callerSub.equals(parties.getBuyerId()) && !callerSub.equals(parties.getSellerId())) {
            throw new AccessDeniedException("Caller is not a party to transaction " + id);
        }
        return new StatusPair(parties.getSellerStatus(), parties.getBuyerStatus());
    }

    /**
     * The booking is priced against the listing as tripservice owns it, so a client cannot
     * decide what it is going to pay by sending its own total, listingInfo or sellerId.
     */
    @Transactional
    public Transaction create(Transaction body, Jwt caller) {
        String buyerId = CallerIdentity.subOf(caller);

        if (body.getListingId() == null) {
            throw new IllegalArgumentException("listingId is required");
        }
        BigDecimal weight = body.getWeight();
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }
        // Le voyageur transporte ce qu'on lui confie : il doit savoir quoi avant d'accepter, et
        // l'acheteur s'engage sur la liste des objets interdits. Aucune reservation sans les deux.
        String content = body.getContentDescription() == null ? "" : body.getContentDescription().trim();
        if (content.isEmpty() || content.length() > 500) {
            throw new BusinessException("content_description_required",
                    "Describe what the traveller will carry (500 characters at most).");
        }
        if (!body.isProhibitedItemsAccepted()) {
            throw new BusinessException("prohibited_items_not_accepted",
                    "The list of prohibited items must be accepted before booking.");
        }

        TripSnapshot trip = tripClient.fetch(body.getListingId());
        if (trip == null || trip.getUserId() == null) {
            throw new NoSuchElementException("Listing not found: " + body.getListingId());
        }
        if (buyerId.equals(trip.getUserId())) {
            throw new IllegalArgumentException("A traveller cannot book their own listing");
        }
        // Recalcule plutot que lu dans trip.active : cette colonne n'est remise a jour qu'a
        // l'ecriture de l'annonce, et reste vraie apres le depart tant que personne n'y touche.
        if (!isBookable(trip)) {
            throw new IllegalArgumentException("Listing is no longer available");
        }
        if (trip.getPricePerKg() == null) {
            throw new IllegalArgumentException("Listing has no price");
        }

        if (trip.getRemainingWeight() == null || weight.compareTo(trip.getRemainingWeight()) > 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }

        Transaction tx = new Transaction();
        tx.setListingId(trip.getId());
        tx.setListingInfo(listingInfoOf(trip));
        tx.setBuyerId(buyerId);
        tx.setBuyerInfo(CallerIdentity.fromToken(caller, body.getBuyerInfo()));
        tx.setSellerId(trip.getUserId());
        tx.setWeight(weight);
        tx.setTotal(trip.getPricePerKg().multiply(weight).setScale(2, RoundingMode.HALF_UP));
        StatusPair initial = stateMachine.initialPair();
        tx.setSellerStatus(initial.sellerStatus());
        tx.setBuyerStatus(initial.buyerStatus());
        tx.setBuyerReview(false);
        tx.setSellerReview(false);
        tx.setContentDescription(content);
        tx.setProhibitedItemsAccepted(true);
        // Payment fields are only ever written by the Stripe webhook (markPaid).
        Transaction saved = transactionRepository.save(tx);
        events.publishEvent(TransactionStatusChanged.of(saved, Actor.BUYER));
        return saved;
    }

    /**
     * Moves the transaction through the state machine. Both status columns travel together --
     * that is how the flow is modelled -- so what is checked here is that the requested move is
     * a real edge of the machine and that the caller is on the side allowed to take it. Money
     * fields are never taken from the body: a re-priced request is recomputed from the listing,
     * and payment is settled through Stripe.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Transaction update(Long id, Transaction body, String callerSub) {
        // --- Phase 1 : lecture courte, pour savoir quelle arete du graphe est demandee.
        Transaction snapshot = findOrThrow(id);
        requireParticipant(snapshot, callerSub);
        Actor actor = callerSub.equals(snapshot.getBuyerId()) ? Actor.BUYER : Actor.SELLER;
        Transition planned = stateMachine
                .resolve(StatusPair.of(snapshot), requestedPair(snapshot, body), actor)
                .orElse(null);

        // --- Phase 2 : appels a tripservice, aucune connexion DB retenue pendant l'attente.
        Repricing repricing = null;
        BigDecimal reservedWeight = null;
        if (planned != null) {
            switch (planned.effect()) {
                case REPRICE -> repricing = quote(snapshot.getListingId(), body.getWeight());
                case RESERVE_CAPACITY -> {
                    reserveCapacity(snapshot.getListingId(), snapshot.getWeight(), id);
                    reservedWeight = snapshot.getWeight();
                }
                // Rendre le poids attend que l'annulation soit ecrite (phase 4) : rendu trop tot,
                // il pourrait etre revendu alors que la transaction n'a finalement pas bouge.
                case SETTLE_PAYMENT, RELEASE_CAPACITY, NONE -> { }
            }
        }

        // --- Phase 3 : ecriture courte, sous verrou de ligne.
        Effect expected = planned == null ? null : planned.effect();
        Repricing priced = repricing;
        BigDecimal reserved = reservedWeight;
        Transaction updated;
        try {
            updated = writeTx.execute(status ->
                    applyUpdate(id, body, callerSub, actor, expected, priced, reserved));
        } catch (RuntimeException ex) {
            if (reserved != null) {
                compensateReservation(id, snapshot.getListingId());
            }
            throw ex;
        }

        // --- Phase 4 : effets distants qui ne doivent suivre qu'une ecriture validee.
        if (expected == Effect.RELEASE_CAPACITY
                && stateMachine.cancelledPair().equals(StatusPair.of(updated))) {
            releaseCapacity(updated.getListingId(), id);
        }
        return updated;
    }

    /**
     * Le poids a ete pris en phase 2 mais la transaction n'a pas ete acceptee : on le rend, sauf
     * si entre-temps un autre appel a bel et bien fait passer la transaction dans un etat qui le
     * detient (double-clic dont le premier clic a abouti, paiement deja arrive).
     */
    private void compensateReservation(Long id, Long listingId) {
        try {
            Transaction current = transactionRepository.findById(id).orElse(null);
            if (current != null && stateMachine.holdsCapacity(StatusPair.of(current))) {
                return;
            }
            tripClient.release(listingId, id);
        } catch (RuntimeException ex) {
            log.error("Capacity reserved for transaction {} on listing {} could not be given back; "
                    + "release it by replaying POST /trips/internal/{}/release", id, listingId, listingId, ex);
            meters.counter("bagbuddy.capacity.release.failures", "cause", "compensation").increment();
        }
    }

    /**
     * Apres une annulation validee. Un echec ici laisse le poids pris -- la direction sure : une
     * annonce qui affiche moins de place qu'elle n'en a, jamais l'inverse. L'appel est idempotent,
     * le rejouer a la main suffit.
     */
    private void releaseCapacity(Long listingId, Long id) {
        try {
            tripClient.release(listingId, id);
        } catch (RuntimeException ex) {
            log.error("Transaction {} was cancelled but its capacity on listing {} was not given back; "
                    + "replay POST /trips/internal/{}/release", id, listingId, listingId, ex);
            meters.counter("bagbuddy.capacity.release.failures", "cause", "cancellation").increment();
        }
    }

    /**
     * Relit la ligne sous verrou et rejoue la resolution : entre la phase 1 et ici, l'autre
     * partie a pu bouger la transaction, auquel cas les effets distants deja joues ne
     * correspondent plus a la transition et la mise a jour est refusee plutot qu'appliquee.
     */
    private Transaction applyUpdate(Long id, Transaction body, String callerSub,
                                    Actor actor, Effect expected, Repricing repricing,
                                    BigDecimal reservedWeight) {
        Transaction existing = transactionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
        requireParticipant(existing, callerSub);

        // Each side owns its own "I have written my review" flag.
        if (actor == Actor.BUYER && body.getBuyerReview() != null) {
            existing.setBuyerReview(body.getBuyerReview());
        }
        if (actor == Actor.SELLER && body.getSellerReview() != null) {
            existing.setSellerReview(body.getSellerReview());
        }

        StatusPair to = requestedPair(existing, body);
        Transition transition = stateMachine.resolve(StatusPair.of(existing), to, actor).orElse(null);
        if (transition == null) {
            return transactionRepository.save(existing);
        }
        if (transition.effect() != expected) {
            throw new IllegalStateException(
                    "Transaction " + id + " changed while it was being updated; retry");
        }

        switch (transition.effect()) {
            case REPRICE -> {
                existing.setWeight(repricing.weight());
                existing.setTotal(repricing.total());
            }
            case SETTLE_PAYMENT -> {
                settlePayment(existing);
                // Paye : le code de remise que l'acheteur donnera au destinataire.
                if (existing.getHandoverCode() == null) {
                    existing.setHandoverCode(newHandoverCode());
                }
            }
            case RESERVE_CAPACITY -> {
                // Refusee puis re-tarifee entre la phase 1 et ici : le poids reserve n'est plus
                // celui de la demande. On refuse, et la compensation rend ce qui a ete pris.
                if (existing.getWeight() == null || existing.getWeight().compareTo(reservedWeight) != 0) {
                    throw new IllegalStateException(
                            "Transaction " + id + " changed while it was being updated; retry");
                }
            }
            case RELEASE_CAPACITY, NONE -> { }
        }

        return reach(existing, to, actor);
    }

    /**
     * Le passage a une nouvelle paire de statuts, par ou passe toute transition appliquee : les
     * deux colonnes voyagent ensemble, le reglement se planifie, et l'evenement est publie dans la
     * transaction d'ecriture -- l'email ne part donc qu'une fois celle-ci validee. Tout ce qui
     * s'ajoutera a une transition (une trace, une metrique) se met ici, et vaut pour les deux
     * chemins vers completed : la cloture par l'acheteur et le code de remise saisi par le voyageur.
     */
    private Transaction reach(Transaction tx, StatusPair to, Actor actor) {
        tx.setSellerStatus(to.sellerStatus());
        tx.setBuyerStatus(to.buyerStatus());
        planSettlement(tx, actor);
        events.publishEvent(TransactionStatusChanged.of(tx, actor));
        return transactionRepository.save(tx);
    }

    /**
     * Annule les demandes jamais payees dont le vol est deja parti : refusees, en attente de
     * reponse, ou acceptees sans paiement. Sans cela elles restent a attendre une action que plus
     * personne ne peut faire, et une acceptation garde son poids pris sur l'annonce -- ce qui
     * interdit ensuite au voyageur de la supprimer.
     *
     * Chaque transaction est reprise sous verrou et revalidee : entre la selection et l'ecriture,
     * l'acheteur a pu payer. Une par une, pour qu'un echec n'emporte pas le reste du lot.
     *
     * @return le nombre de transactions annulees
     */
    // Hors transaction, comme update() : sinon chaque ecriture de writeTx rejoindrait la transaction
    // readOnly de la classe, et rien ne serait flushe.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireDepartedRequests() {
        String now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString();
        int expired = 0;
        for (Long id : transactionRepository.findDepartedWithStatus(now, stateMachine.expirableKeys())) {
            try {
                if (expireOne(id, now)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("Transaction {} could not be expired; it will be retried on the next run", id, ex);
            }
        }
        if (expired > 0) {
            meters.counter("bagbuddy.transaction.expired").increment(expired);
            log.info("Expired {} unpaid transaction(s) whose flight has departed", expired);
        }
        return expired;
    }

    private record Expiry(Long listingId, boolean heldCapacity) { }

    private boolean expireOne(Long id, String now) {
        Expiry expiry = writeTx.execute(status -> {
            Transaction tx = transactionRepository.findByIdForUpdate(id).orElse(null);
            if (tx == null || !stateMachine.isExpirable(StatusPair.of(tx))) {
                return null;
            }
            String departure = tx.getListingInfo() == null ? null : tx.getListingInfo().getDepartureDate();
            if (departure == null || departure.length() < 16 || departure.compareTo(now) >= 0) {
                return null;
            }
            boolean held = stateMachine.holdsCapacity(StatusPair.of(tx));
            StatusPair cancelled = stateMachine.cancelledPair();
            tx.setSellerStatus(cancelled.sellerStatus());
            tx.setBuyerStatus(cancelled.buyerStatus());
            // Aucun acteur : ni l'acheteur ni le vendeur n'a fait ce geste.
            events.publishEvent(TransactionStatusChanged.of(tx, Actor.SYSTEM));
            transactionRepository.save(tx);
            return new Expiry(tx.getListingId(), held);
        });
        if (expiry == null) {
            return false;
        }
        if (expiry.heldCapacity()) {
            releaseCapacity(expiry.listingId(), id);
        }
        return true;
    }

    /**
     * Dans la transaction d'ecriture, quel que soit le chemin (updateTransaction, confirmHandover) :
     * une transaction payee qui se termine ou s'annule recoit ses montants de reglement en meme
     * temps que son nouveau statut. Sans paiement, rien a regler. Les appels a Stripe, eux, ne
     * retiennent pas la requete : ils partent apres le commit, sur un autre thread.
     */
    private void planSettlement(Transaction tx, Actor actor) {
        StatusPair reached = StatusPair.of(tx);
        if (stateMachine.completedPair().equals(reached)) {
            settlement.planCompletion(tx);
        } else if (stateMachine.cancelledPair().equals(reached)) {
            settlement.planCancellation(tx, actor);
        }
        // Execute apres le commit, hors de la requete (SettlementService.onSettlementDue).
        if (settlement.hasPendingWork(tx)) {
            events.publishEvent(new SettlementDue(tx.getId()));
        }
    }

    private StatusPair requestedPair(Transaction existing, Transaction body) {
        return new StatusPair(
                body.getSellerStatus() == null ? existing.getSellerStatus() : body.getSellerStatus(),
                body.getBuyerStatus() == null ? existing.getBuyerStatus() : body.getBuyerStatus());
    }

    /** Poids et prix recalcules depuis l'annonce, jamais pris dans le corps de la requete. */
    private record Repricing(BigDecimal weight, BigDecimal total) { }

    /** A new request with a different weight: the price is recomputed from the listing. */
    private Repricing quote(Long listingId, BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }
        TripSnapshot trip = tripClient.fetch(listingId);
        if (trip == null || trip.getPricePerKg() == null) {
            throw new NoSuchElementException("Listing not found: " + listingId);
        }
        if (trip.getRemainingWeight() == null || weight.compareTo(trip.getRemainingWeight()) > 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }
        return new Repricing(weight,
                trip.getPricePerKg().multiply(weight).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The seller accepting is the commitment point, so this is where the weight leaves the
     * listing -- decided by tripservice under a row lock, never by the caller. The reservation is
     * keyed by the transaction, so a second "accept" does not take the weight again.
     */
    private void reserveCapacity(Long listingId, BigDecimal weight, Long transactionId) {
        TripSnapshot reserved = tripClient.reserve(listingId, weight, transactionId);
        if (reserved == null) {
            throw new IllegalStateException(
                    "Capacity reservation returned nothing for listing " + listingId);
        }
    }

    /**
     * Confirming a payment. With Stripe wired in, the transaction must already carry the
     * paidAt written by the signed webhook -- the browser saying "I paid" is not evidence.
     * And a paidAt alone is not enough either: the amount Stripe charged must still be the
     * total owed, or a small payment could be carried over to a larger booking.
     */
    private void settlePayment(Transaction existing) {
        if (requireStripe) {
            if (existing.getPaidAt() == null) {
                throw new IllegalArgumentException(
                        "Payment has not been confirmed by Stripe for transaction " + existing.getId());
            }
            if (!Long.valueOf(Money.minorUnits(existing.getTotal())).equals(existing.getStripeAmount())) {
                throw new IllegalArgumentException(
                        "The recorded payment does not cover the total of transaction " + existing.getId());
            }
            return;
        }
        if (existing.getPaidAt() == null) {
            existing.setPaidAt(LocalDateTime.now());
        }
    }

    /**
     * Called only by the Stripe webhook, through the service-role internal endpoint.
     *
     * A payment is only recorded against a transaction that is actually waiting for it, and only
     * for the exact total: a PaymentIntent created earlier, for a smaller amount, must not be
     * able to settle a booking that has since been re-priced. Refusals are IllegalArgumentException
     * (400), which stripeservice treats as final rather than asking Stripe to retry.
     */
    @Transactional
    public Transaction markPaid(Long id, String paymentIntentId, Long amount, String currency) {
        Transaction tx = transactionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
        if (tx.getPaidAt() != null) {
            // Stripe retries webhooks; recording the same payment twice must be a no-op.
            return tx;
        }
        if (!stateMachine.awaitingPaymentPair().equals(StatusPair.of(tx))) {
            throw new IllegalArgumentException("Transaction " + id + " is not awaiting payment");
        }
        if (amount == null || amount != Money.minorUnits(tx.getTotal())) {
            throw new IllegalArgumentException(
                    "Paid amount " + amount + " does not match the total of transaction " + id);
        }
        tx.setStripePaymentIntentId(paymentIntentId);
        tx.setStripeAmount(amount);
        tx.setStripeCurrency(currency);
        tx.setPaidAt(LocalDateTime.now());
        return transactionRepository.save(tx);
    }

    /**
     * Le voyageur clot la transaction en saisissant le code que le destinataire lui donne a la
     * livraison. C'est la preuve de remise : l'acheteur a recu ce code a son paiement et ne l'a
     * donne qu'a la personne qui recoit le colis.
     *
     * Les essais sont comptes dans la transaction d'ecriture, qui est validee meme pour un code
     * faux : l'erreur n'est levee qu'apres, pour que le compteur ne soit pas annule avec elle. Au
     * bout de Transaction.MAX_HANDOVER_ATTEMPTS le code est bloque, ce qui rend vaine une recherche
     * par essais successifs (6 chiffres, 5 essais).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Transaction confirmHandover(Long id, String code, String callerSub) {
        Transaction tx = writeTx.execute(status -> {
            Transaction current = transactionRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
            requireParticipant(current, callerSub);
            if (!callerSub.equals(current.getSellerId())) {
                throw new AccessDeniedException("Only the traveller confirms the handover");
            }
            if (!stateMachine.confirmedPair().equals(StatusPair.of(current)) || current.getHandoverCode() == null) {
                throw new BusinessException("handover_not_expected",
                        "This transaction is not waiting for a handover.");
            }
            if (current.isHandoverLocked()) {
                return current;
            }
            String submitted = code == null ? "" : code.replaceAll("\\s", "");
            if (!MessageDigest.isEqual(
                    submitted.getBytes(StandardCharsets.UTF_8),
                    current.getHandoverCode().getBytes(StandardCharsets.UTF_8))) {
                current.setHandoverAttempts(current.getHandoverAttempts() + 1);
                return transactionRepository.save(current);
            }
            return reach(current, stateMachine.completedPair(), Actor.SELLER);
        });
        if (stateMachine.isCompleted(StatusPair.of(tx))) {
            return tx;
        }
        throw tx.isHandoverLocked()
                ? new BusinessException("handover_locked", "Too many wrong codes: the buyer has to confirm the delivery.")
                : new BusinessException("invalid_handover_code", "This handover code is not the right one.");
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String newHandoverCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    @Transactional
    public void delete(Long id, String callerSub) {
        Transaction tx = findOrThrow(id);
        requireParticipant(tx, callerSub);
        // Acceptee ou payee, la transaction tient du poids sur l'annonce, et peut-etre l'argent de
        // l'acheteur : la supprimer effacerait l'engagement sans rien rendre. On annule d'abord.
        if (stateMachine.holdsCapacity(StatusPair.of(tx)) && !stateMachine.isCompleted(StatusPair.of(tx))) {
            throw new IllegalArgumentException(
                    "An accepted or paid transaction must be cancelled before it can be deleted");
        }
        transactionRepository.delete(tx);
    }

    /** Meme formule que TripListener, appliquee a l'instant de la reservation. */
    private static boolean isBookable(TripSnapshot trip) {
        return trip.getRemainingWeight() != null
                && trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0
                && trip.getDepartureDate() != null
                && trip.getDepartureDate().isAfter(LocalDateTime.now());
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
    }

    private void requireParticipant(Transaction tx, String callerSub) {
        if (!callerSub.equals(tx.getBuyerId()) && !callerSub.equals(tx.getSellerId())) {
            throw new AccessDeniedException("Caller is not a party to transaction " + tx.getId());
        }
    }

    private ListingInfo listingInfoOf(TripSnapshot trip) {
        ListingInfo info = new ListingInfo();
        info.setDepartureAirport(trip.getDepartureAirport());
        info.setArrivalAirport(trip.getArrivalAirport());
        info.setDepartureDate(trip.getDepartureDate() == null ? null : trip.getDepartureDate().toString());
        info.setArrivalDate(trip.getArrivalDate() == null ? null : trip.getArrivalDate().toString());
        info.setTotalWeightAvailable(trip.getTotalWeightAvailable() == null ? 0d : trip.getTotalWeightAvailable().doubleValue());
        info.setRemainingWeight(trip.getRemainingWeight() == null ? 0d : trip.getRemainingWeight().doubleValue());
        info.setPricePerKg(trip.getPricePerKg().doubleValue());
        info.setConditions(trip.getConditions());
        info.setCreatedAt(trip.getCreatedAt() == null ? LocalDateTime.now().toString() : trip.getCreatedAt().toString());
        info.setSellerUserInfo(sellerInfoOf(trip.getUserInfo()));
        return info;
    }

    private UserInfo sellerInfoOf(TripSnapshot.SellerInfo source) {
        UserInfo info = new UserInfo();
        if (source == null) {
            return info;
        }
        info.setSub(source.getSub());
        info.setEmail(source.getEmail());
        info.setEmail_verified(source.isEmail_verified());
        info.setFamily_name(source.getFamily_name());
        info.setGiven_name(source.getGiven_name());
        info.setName(source.getName());
        info.setUsername(source.getUsername());
        info.setBio(source.getBio());
        info.setLocation(source.getLocation());
        info.setPhone(source.getPhone());
        return info;
    }
}

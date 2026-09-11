package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Effect;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.StatusPair;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Transition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TripClient tripClient;
    private final TransactionStateMachine stateMachine;
    /** Ecriture programmatique : la phase d'ecriture de update() ne peut pas passer par le proxy. */
    private final TransactionTemplate writeTx;

    public TransactionService(TransactionRepository transactionRepository,
                              TripClient tripClient,
                              TransactionStateMachine stateMachine,
                              TransactionTemplate writeTx) {
        this.transactionRepository = transactionRepository;
        this.tripClient = tripClient;
        this.stateMachine = stateMachine;
        this.writeTx = writeTx;
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

        TripSnapshot trip = tripClient.fetch(body.getListingId());
        if (trip == null || trip.getUserId() == null) {
            throw new NoSuchElementException("Listing not found: " + body.getListingId());
        }
        if (buyerId.equals(trip.getUserId())) {
            throw new IllegalArgumentException("A traveller cannot book their own listing");
        }
        if (!Boolean.TRUE.equals(trip.getActive())) {
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
        // Payment fields are only ever written by the Stripe webhook (markPaid).
        return transactionRepository.save(tx);
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
                .resolve(pairOf(snapshot), requestedPair(snapshot, body), actor)
                .orElse(null);

        // --- Phase 2 : appels a tripservice, aucune connexion DB retenue pendant l'attente.
        Repricing repricing = null;
        if (planned != null) {
            switch (planned.effect()) {
                case REPRICE -> repricing = quote(snapshot.getListingId(), body.getWeight());
                case RESERVE_CAPACITY -> reserveCapacity(snapshot.getListingId(), snapshot.getWeight());
                case SETTLE_PAYMENT, NONE -> { }
            }
        }

        // --- Phase 3 : ecriture courte, sous verrou de ligne.
        Effect expected = planned == null ? null : planned.effect();
        Repricing priced = repricing;
        return writeTx.execute(status -> applyUpdate(id, body, callerSub, actor, expected, priced));
    }

    /**
     * Relit la ligne sous verrou et rejoue la resolution : entre la phase 1 et ici, l'autre
     * partie a pu bouger la transaction, auquel cas les effets distants deja joues ne
     * correspondent plus a la transition et la mise a jour est refusee plutot qu'appliquee.
     */
    private Transaction applyUpdate(Long id, Transaction body, String callerSub,
                                    Actor actor, Effect expected, Repricing repricing) {
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
        Transition transition = stateMachine.resolve(pairOf(existing), to, actor).orElse(null);
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
            case SETTLE_PAYMENT -> settlePayment(existing);
            case RESERVE_CAPACITY, NONE -> { }
        }

        existing.setSellerStatus(to.sellerStatus());
        existing.setBuyerStatus(to.buyerStatus());
        return transactionRepository.save(existing);
    }

    private StatusPair pairOf(Transaction tx) {
        return new StatusPair(tx.getSellerStatus(), tx.getBuyerStatus());
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
     * listing -- decided by tripservice under a row lock, never by the caller.
     */
    private void reserveCapacity(Long listingId, BigDecimal weight) {
        TripSnapshot reserved = tripClient.reserve(listingId, weight);
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
            if (!Long.valueOf(minorUnits(existing.getTotal())).equals(existing.getStripeAmount())) {
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
        if (!stateMachine.awaitingPaymentPair().equals(pairOf(tx))) {
            throw new IllegalArgumentException("Transaction " + id + " is not awaiting payment");
        }
        if (amount == null || amount != minorUnits(tx.getTotal())) {
            throw new IllegalArgumentException(
                    "Paid amount " + amount + " does not match the total of transaction " + id);
        }
        tx.setStripePaymentIntentId(paymentIntentId);
        tx.setStripeAmount(amount);
        tx.setStripeCurrency(currency);
        tx.setPaidAt(LocalDateTime.now());
        return transactionRepository.save(tx);
    }

    @Transactional
    public void delete(Long id, String callerSub) {
        Transaction tx = findOrThrow(id);
        requireParticipant(tx, callerSub);
        transactionRepository.delete(tx);
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
    }

    /** Same conversion stripeservice uses to build the PaymentIntent amount. */
    private static long minorUnits(BigDecimal total) {
        if (total == null) {
            throw new IllegalArgumentException("Transaction has no total");
        }
        return total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
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

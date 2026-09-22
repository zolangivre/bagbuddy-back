package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.client.StripeClient;
import com.bagbuddy.transactionservice.client.StripeClient.TransferResult;
import com.bagbuddy.transactionservice.model.SettlementStatus;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.service.SettlementPolicy.Plan;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Le reglement d'une transaction payee : rembourser l'acheteur, verser le voyageur.
 *
 * En deux temps, comme une boite d'envoi :
 * <ol>
 *   <li><b>Planifier</b>, dans la transaction d'ecriture qui fait passer la transaction a
 *   {@code completed} ou {@code cancelled} : les montants et un statut PENDING sont ecrits avec le
 *   changement de statut, ou pas du tout. Aucune transition validee ne peut donc "oublier" de
 *   rembourser.</li>
 *   <li><b>Executer</b>, apres le commit et sur un autre thread : appel a stripeservice, puis
 *   enregistrement du resultat (relu sous verrou : la requete d'origine est deja repartie).
 *   Ce qui echoue reste PENDING et SettlementJob le relance ; stripeservice ne paie jamais deux fois
 *   la meme transaction, relancer est donc sans danger.</li>
 * </ol>
 *
 * Un paiement simule (PAYMENTS_REQUIRE_STRIPE=false, pas de PaymentIntent) est reparti de la meme
 * facon mais marque SIMULATED, sans appel a Stripe : la demo affiche les memes montants.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final TransactionRepository transactionRepository;
    private final SettlementPolicy policy;
    private final StripeClient stripe;
    private final TransactionTemplate writeTx;
    private final MeterRegistry meters;

    public SettlementService(TransactionRepository transactionRepository,
                             SettlementPolicy policy,
                             StripeClient stripe,
                             TransactionTemplate writeTx,
                             MeterRegistry meters) {
        this.transactionRepository = transactionRepository;
        this.policy = policy;
        this.stripe = stripe;
        this.writeTx = writeTx;
        this.meters = meters;
    }

    // --- Planifier : appele dans la transaction d'ecriture du changement de statut ---

    public void planCompletion(Transaction tx) {
        if (!isPaid(tx) || alreadyPlanned(tx)) {
            return;
        }
        apply(tx, policy.onCompletion(charged(tx)));
    }

    public void planCancellation(Transaction tx, Actor cancelledBy) {
        if (!isPaid(tx) || alreadyPlanned(tx)) {
            return;
        }
        apply(tx, policy.onCancellation(charged(tx), cancelledBy, departureOf(tx), LocalDateTime.now()));
    }

    private void apply(Transaction tx, Plan plan) {
        boolean simulated = tx.getStripePaymentIntentId() == null;
        SettlementStatus due = simulated ? SettlementStatus.SIMULATED : SettlementStatus.PENDING;
        LocalDateTime now = LocalDateTime.now();

        tx.setPlatformFee(plan.platformFee());
        tx.setRefundAmount(plan.refund());
        tx.setPayoutAmount(plan.payout());
        if (plan.refund() > 0) {
            tx.setRefundStatus(due);
            tx.setRefundedAt(simulated ? now : null);
        }
        if (plan.payout() > 0) {
            tx.setPayoutStatus(due);
            tx.setPaidOutAt(simulated ? now : null);
        }
    }

    public boolean hasPendingWork(Transaction tx) {
        return tx.getRefundStatus() == SettlementStatus.PENDING
                || tx.getPayoutStatus() == SettlementStatus.PENDING
                || tx.getPayoutStatus() == SettlementStatus.AWAITING_ACCOUNT;
    }

    // --- Executer : apres le commit, et depuis SettlementJob ---

    /**
     * Declenche par planSettlement, une fois la transition validee. Hors de la requete : un versement
     * peut enchainer plusieurs appels distants (stripeservice, puis Stripe et userservice), que le
     * membre n'a pas a attendre -- la reponse montre le reglement PENDING, le front le relit.
     * Une ecriture annulee ne declenche rien ; un echec ici laisse PENDING pour SettlementJob.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementDue(SettlementDue event) {
        settle(event.transactionId());
    }

    /** Ne leve jamais : un echec est journalise, compte, et laisse en attente de relance. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void settle(Long id) {
        Transaction tx = transactionRepository.findById(id).orElse(null);
        if (tx == null) {
            return;
        }
        if (tx.getRefundStatus() == SettlementStatus.PENDING) {
            executeRefund(tx);
        }
        if (tx.getPayoutStatus() == SettlementStatus.PENDING
                || tx.getPayoutStatus() == SettlementStatus.AWAITING_ACCOUNT) {
            executePayout(tx);
        }
    }

    /** @return le nombre de transactions examinees */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int settlePending() {
        List<Long> ids = transactionRepository.findWithPendingSettlement(
                SettlementStatus.PENDING, SettlementStatus.AWAITING_ACCOUNT);
        ids.forEach(this::settle);
        return ids.size();
    }

    private void executeRefund(Transaction tx) {
        Long id = tx.getId();
        attempt("refund", id, tx.getRefundAmount(), t -> t.setRefundStatus(SettlementStatus.FAILED), () -> {
            String refundId = stripe.refund(id, tx.getStripePaymentIntentId(), tx.getRefundAmount());
            record(id, t -> {
                t.setRefundStatus(SettlementStatus.DONE);
                t.setStripeRefundId(refundId);
                t.setRefundedAt(LocalDateTime.now());
            });
        });
    }

    private void executePayout(Transaction tx) {
        Long id = tx.getId();
        attempt("payout", id, tx.getPayoutAmount(), t -> t.setPayoutStatus(SettlementStatus.FAILED), () -> {
            TransferResult result = stripe.transfer(id, tx.getStripePaymentIntentId(), tx.getSellerId(), tx.getPayoutAmount());
            if (result.status() == StripeClient.TransferStatus.PAID) {
                record(id, t -> {
                    t.setPayoutStatus(SettlementStatus.DONE);
                    t.setStripeTransferId(result.transferId());
                    t.setPaidOutAt(LocalDateTime.now());
                });
            } else if (tx.getPayoutStatus() != SettlementStatus.AWAITING_ACCOUNT) {
                // Le voyageur n'a pas (encore) de compte de versement : l'argent attend sur la
                // plateforme, et la relance le versera des que l'onboarding sera fait.
                record(id, t -> t.setPayoutStatus(SettlementStatus.AWAITING_ACCOUNT));
            }
        });
    }

    /**
     * Ce qu'on fait d'un mouvement d'argent qui echoue, pour les deux sortes a la fois : un refus
     * definitif (400) est enregistre FAILED et laisse a un humain, une indisponibilite laisse la
     * ligne en attente et SettlementJob la reprendra. Un echec ne defait jamais la transition qui
     * l'a declenche.
     *
     * @param kind       "refund" ou "payout" : l'etiquette de la metrique
     * @param markFailed comment marquer ce mouvement-ci comme definitivement refuse
     */
    private void attempt(String kind, Long id, Long amount, Consumer<Transaction> markFailed, Runnable move) {
        try {
            move.run();
        } catch (IllegalArgumentException refused) {
            record(id, markFailed);
            failure(kind, "refused");
            log.error("Settlement {} of {} for transaction {} was refused by Stripe and needs a human: {}",
                    kind, amount, id, refused.getMessage());
        } catch (RuntimeException unavailable) {
            failure(kind, "unavailable");
            log.warn("Settlement {} for transaction {} could not be executed yet; it will be retried",
                    kind, id, unavailable);
        }
    }

    private void record(Long id, Consumer<Transaction> change) {
        writeTx.executeWithoutResult(status -> transactionRepository.findByIdForUpdate(id).ifPresent(t -> {
            change.accept(t);
            transactionRepository.save(t);
        }));
    }

    private void failure(String kind, String cause) {
        meters.counter("bagbuddy.settlement.failures", "kind", kind, "cause", cause).increment();
    }

    private static boolean isPaid(Transaction tx) {
        return tx.getPaidAt() != null;
    }

    /** Un reglement ne se planifie qu'une fois : une transaction ne se termine qu'une fois. */
    private static boolean alreadyPlanned(Transaction tx) {
        return tx.getRefundStatus() != null || tx.getPayoutStatus() != null;
    }

    /** Ce que Stripe a encaisse, ou le total pour un paiement simule. */
    private static long charged(Transaction tx) {
        if (tx.getStripeAmount() != null) {
            return tx.getStripeAmount();
        }
        return Money.minorUnits(tx.getTotal());
    }

    private static LocalDateTime departureOf(Transaction tx) {
        String departure = tx.getListingInfo() == null ? null : tx.getListingInfo().getDepartureDate();
        if (departure == null || departure.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(departure);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}

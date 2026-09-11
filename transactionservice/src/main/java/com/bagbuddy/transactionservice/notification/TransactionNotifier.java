package com.bagbuddy.transactionservice.notification;

import com.bagbuddy.transactionservice.config.TransactionStatusProperties;
import com.bagbuddy.transactionservice.notification.TransactionStatusChanged.Party;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Tells the other party that a transaction moved, by email.
 *
 * Three guarantees, each load-bearing:
 * <ul>
 *   <li><b>After commit only.</b> A transition refused or rolled back must not announce a change
 *   that never happened — hence {@code AFTER_COMMIT}, and the event published inside the write
 *   transaction rather than after it.</li>
 *   <li><b>Never on the request path.</b> {@code @Async}: an SMTP server that hangs for five
 *   seconds must not hold the seller's "accept" click, and a failure must not turn a successful
 *   transition into an error.</li>
 *   <li><b>Never to the actor.</b> Whoever made the move saw it happen on screen.</li>
 * </ul>
 *
 * The web front has no push channel: this email, and the pending-actions badge the front
 * computes on its own, are how a member learns that it is their turn.
 */
@Component
public class TransactionNotifier {

    private static final Logger log = LoggerFactory.getLogger(TransactionNotifier.class);

    /** What happened, from the recipient's point of view. */
    public enum Kind {
        NEW_REQUEST, REQUEST_ACCEPTED, REQUEST_DECLINED, PAYMENT_CONFIRMED, COMPLETED, CANCELLED
    }

    public record Notice(Kind kind, Party recipient, Party counterpart) { }

    private final TransactionStatusProperties status;
    private final TransactionMailer mailer;
    private final boolean enabled;

    public TransactionNotifier(TransactionStatusProperties status, TransactionMailer mailer,
                               @Value("${bagbuddy.notifications.enabled:true}") boolean enabled) {
        this.status = status;
        this.mailer = mailer;
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(TransactionStatusChanged event) {
        if (!enabled) {
            return;
        }
        noticeFor(event).ifPresent(notice -> {
            if (notice.recipient().email() == null || notice.recipient().email().isBlank()) {
                log.warn("No email on file for the recipient of {} on transaction {}",
                        notice.kind(), event.transactionId());
                return;
            }
            try {
                mailer.send(notice, event);
            } catch (RuntimeException ex) {
                // Le changement de statut est valide et visible dans l'app : seul l'email manque.
                log.error("Notification {} for transaction {} could not be sent",
                        notice.kind(), event.transactionId(), ex);
            }
        });
    }

    /**
     * Which email, to whom. Keyed on the pair reached rather than on the edge taken: a new
     * request after a refusal lands on the same pair as a first request, and says the same thing.
     */
    Optional<Notice> noticeFor(TransactionStatusChanged event) {
        String seller = event.sellerStatus();
        String buyer = event.buyerStatus();
        Party toSeller = event.seller();
        Party toBuyer = event.buyer();

        if (is(seller, status.getReservationReceived()) && is(buyer, status.getWaitingForResponseBuyer())) {
            return notice(Kind.NEW_REQUEST, toSeller, toBuyer, event.actor(), Actor.SELLER);
        }
        if (is(seller, status.getAwaitingPayment()) && is(buyer, status.getPaymentRequired())) {
            return notice(Kind.REQUEST_ACCEPTED, toBuyer, toSeller, event.actor(), Actor.BUYER);
        }
        if (is(seller, status.getWaitingForResponseSeller()) && is(buyer, status.getRequestRejected())) {
            return notice(Kind.REQUEST_DECLINED, toBuyer, toSeller, event.actor(), Actor.BUYER);
        }
        if (is(seller, status.getConfirmed()) && is(buyer, status.getConfirmed())) {
            return notice(Kind.PAYMENT_CONFIRMED, toSeller, toBuyer, event.actor(), Actor.SELLER);
        }
        boolean completed = is(seller, status.getCompleted()) && is(buyer, status.getCompleted());
        boolean cancelled = is(seller, status.getCancelled()) && is(buyer, status.getCancelled());
        if (completed || cancelled) {
            Kind kind = completed ? Kind.COMPLETED : Kind.CANCELLED;
            // Terminee ou annulee : l'un ou l'autre peut en etre a l'origine, on previent l'autre.
            return event.actor() == Actor.BUYER
                    ? Optional.of(new Notice(kind, toSeller, toBuyer))
                    : Optional.of(new Notice(kind, toBuyer, toSeller));
        }
        return Optional.empty();
    }

    private static Optional<Notice> notice(Kind kind, Party recipient, Party counterpart,
                                           Actor actor, Actor recipientSide) {
        return actor == recipientSide ? Optional.empty() : Optional.of(new Notice(kind, recipient, counterpart));
    }

    private static boolean is(String value, String expected) {
        return expected.equals(value);
    }
}

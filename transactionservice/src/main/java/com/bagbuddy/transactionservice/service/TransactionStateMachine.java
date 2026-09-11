package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.config.TransactionStatusProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The allowed moves of a transaction, enforced server-side.
 *
 * Both status columns move together on every step (that is how the front models it: accepting
 * a request sets the seller to awaiting_payment *and* the buyer to payment_required), so a
 * transition is a pair-to-pair edge rather than a per-column write. What matters for integrity
 * is which side is allowed to trigger which edge -- a buyer must not be able to accept their
 * own booking or declare it complete on the seller's behalf.
 */
@Component
public class TransactionStateMachine {

    public enum Actor { BUYER, SELLER }

    /** Side effect the service must apply when the edge is taken. */
    public enum Effect { NONE, REPRICE, RESERVE_CAPACITY, SETTLE_PAYMENT }

    public record StatusPair(String sellerStatus, String buyerStatus) {
        public StatusPair {
            Objects.requireNonNull(sellerStatus, "sellerStatus");
            Objects.requireNonNull(buyerStatus, "buyerStatus");
        }
    }

    public record Transition(StatusPair from, StatusPair to, List<Actor> allowedActors, Effect effect) {
    }

    private final List<Transition> transitions;
    private final StatusPair initial;
    private final StatusPair awaitingPayment;

    public TransactionStateMachine(TransactionStatusProperties status) {

        StatusPair requested = new StatusPair(status.getReservationReceived(), status.getWaitingForResponseBuyer());
        StatusPair rejected = new StatusPair(status.getWaitingForResponseSeller(), status.getRequestRejected());
        StatusPair accepted = new StatusPair(status.getAwaitingPayment(), status.getPaymentRequired());
        StatusPair confirmed = new StatusPair(status.getConfirmed(), status.getConfirmed());
        StatusPair completed = new StatusPair(status.getCompleted(), status.getCompleted());
        StatusPair cancelled = new StatusPair(status.getCancelled(), status.getCancelled());

        this.initial = requested;
        this.awaitingPayment = accepted;
        this.transitions = List.of(
                // The buyer asks again with a different weight after a refusal: re-priced server-side.
                new Transition(rejected, requested, List.of(Actor.BUYER), Effect.REPRICE),
                // The seller accepts: this is the commitment point, so the weight leaves the listing.
                new Transition(requested, accepted, List.of(Actor.SELLER), Effect.RESERVE_CAPACITY),
                new Transition(requested, rejected, List.of(Actor.SELLER), Effect.NONE),
                // The buyer pays.
                new Transition(accepted, confirmed, List.of(Actor.BUYER), Effect.SETTLE_PAYMENT),
                // Either side may close the deal once it is confirmed, or call it off before that.
                new Transition(confirmed, completed, List.of(Actor.BUYER, Actor.SELLER), Effect.NONE),
                new Transition(requested, cancelled, List.of(Actor.BUYER, Actor.SELLER), Effect.NONE),
                new Transition(rejected, cancelled, List.of(Actor.BUYER, Actor.SELLER), Effect.NONE),
                new Transition(accepted, cancelled, List.of(Actor.BUYER, Actor.SELLER), Effect.NONE),
                new Transition(confirmed, cancelled, List.of(Actor.BUYER, Actor.SELLER), Effect.NONE));
    }

    public StatusPair initialPair() {
        return initial;
    }

    /** The only pair in which a payment may be recorded: the seller has accepted, the buyer owes. */
    public StatusPair awaitingPaymentPair() {
        return awaitingPayment;
    }

    /**
     * @return the edge to apply, or empty when the caller asked for no status change at all.
     * @throws IllegalArgumentException when the move is not part of the state machine
     * @throws org.springframework.security.access.AccessDeniedException when the caller is on
     *         the wrong side of an otherwise valid move
     */
    public Optional<Transition> resolve(StatusPair from, StatusPair to, Actor actor) {
        if (from.equals(to)) {
            return Optional.empty();
        }
        List<Transition> matching = transitions.stream()
                .filter(t -> t.from().equals(from) && t.to().equals(to))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException(
                    "Not a valid transition: %s/%s -> %s/%s".formatted(
                            from.sellerStatus(), from.buyerStatus(), to.sellerStatus(), to.buyerStatus()));
        }
        Transition transition = matching.get(0);
        if (!transition.allowedActors().contains(actor)) {
            throw new AccessDeniedException(
                    "The %s may not perform this transition".formatted(actor.name().toLowerCase()));
        }
        return Optional.of(transition);
    }
}

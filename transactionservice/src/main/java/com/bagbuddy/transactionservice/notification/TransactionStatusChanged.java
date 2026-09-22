package com.bagbuddy.transactionservice.notification;

import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;

import java.math.BigDecimal;

/**
 * A transaction reached a new status pair. Published inside the write transaction and handled
 * only once it has committed (see {@link TransactionNotifier}).
 *
 * Everything the email needs is copied here rather than read later from the entity: the
 * listener runs on another thread, after the persistence context is gone.
 *
 * @param actor the side that made the move — the one who does not need to be told about it
 */
public record TransactionStatusChanged(
        Long transactionId,
        Actor actor,
        String sellerStatus,
        String buyerStatus,
        Party buyer,
        Party seller,
        String departureAirport,
        String arrivalAirport,
        String departureDate,
        BigDecimal weight) {

    public record Party(String email, String firstName) {

        static Party of(UserInfo info) {
            return info == null ? new Party(null, null) : new Party(info.getEmail(), info.getGiven_name());
        }
    }

    public static TransactionStatusChanged of(Transaction tx, Actor actor) {
        ListingInfo listing = tx.getListingInfo() == null ? new ListingInfo() : tx.getListingInfo();
        return new TransactionStatusChanged(
                tx.getId(),
                actor,
                tx.getSellerStatus(),
                tx.getBuyerStatus(),
                Party.of(tx.getBuyerInfo()),
                Party.of(listing.getSellerUserInfo()),
                listing.getDepartureAirport(),
                listing.getArrivalAirport(),
                listing.getDepartureDate(),
                tx.getWeight());
    }
}

package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transaction telle qu'exposee par le schema. Aucun filtrage de champ ici : une transaction est
 * deja reservee a ses participants (requireParticipant dans TransactionService), et les deux
 * parties ont besoin des coordonnees l'une de l'autre pour se retrouver a l'aeroport.
 */
public record TransactionView(
        Long id,
        Long listingId,
        ListingInfoView listingInfo,
        PartyView buyerInfo,
        String buyerId,
        String sellerId,
        String sellerStatus,
        String buyerStatus,
        BigDecimal weight,
        BigDecimal total,
        Boolean sellerReview,
        Boolean buyerReview,
        String stripePaymentIntentId,
        String stripeCurrency,
        Long stripeAmount,
        LocalDateTime paidAt,
        LocalDateTime createdAt) {

    public static TransactionView of(Transaction tx) {
        if (tx == null) {
            return null;
        }
        return new TransactionView(
                tx.getId(),
                tx.getListingId(),
                ListingInfoView.of(tx.getListingInfo()),
                PartyView.of(tx.getBuyerInfo()),
                tx.getBuyerId(),
                tx.getSellerId(),
                tx.getSellerStatus(),
                tx.getBuyerStatus(),
                tx.getWeight(),
                tx.getTotal(),
                tx.getSellerReview(),
                tx.getBuyerReview(),
                tx.getStripePaymentIntentId(),
                tx.getStripeCurrency(),
                tx.getStripeAmount(),
                tx.getPaidAt(),
                tx.getCreatedAt());
    }

    public static List<TransactionView> of(List<Transaction> transactions) {
        return transactions.stream().map(TransactionView::of).toList();
    }
}

package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transaction telle qu'exposee par le schema. Aucun filtrage de champ ici : une transaction est
 * deja reservee a ses participants (requireParticipant dans TransactionService), et les deux
 * parties ont besoin des coordonnees l'une de l'autre pour se retrouver a l'aeroport.
 *
 * Une exception, le code de remise : il n'est rendu qu'a l'acheteur. Le voyageur doit le recevoir
 * du destinataire a la livraison ; le lui montrer ici viderait la preuve de son sens.
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
        LocalDateTime createdAt,
        String contentDescription,
        boolean prohibitedItemsAccepted,
        String handoverCode,
        boolean handoverLocked,
        Long platformFee,
        Long refundAmount,
        String refundStatus,
        LocalDateTime refundedAt,
        Long payoutAmount,
        String payoutStatus,
        LocalDateTime paidOutAt) {

    public static TransactionView of(Transaction tx, String callerSub) {
        if (tx == null) {
            return null;
        }
        boolean buyer = callerSub != null && callerSub.equals(tx.getBuyerId());
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
                tx.getCreatedAt(),
                tx.getContentDescription(),
                tx.isProhibitedItemsAccepted(),
                buyer ? tx.getHandoverCode() : null,
                tx.isHandoverLocked(),
                tx.getPlatformFee(),
                tx.getRefundAmount(),
                tx.getRefundStatus() == null ? null : tx.getRefundStatus().name(),
                tx.getRefundedAt(),
                tx.getPayoutAmount(),
                tx.getPayoutStatus() == null ? null : tx.getPayoutStatus().name(),
                tx.getPaidOutAt());
    }

    public static List<TransactionView> of(List<Transaction> transactions, String callerSub) {
        return transactions.stream().map(tx -> of(tx, callerSub)).toList();
    }
}

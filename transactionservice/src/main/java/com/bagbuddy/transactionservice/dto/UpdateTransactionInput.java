package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.Transaction;

import java.math.BigDecimal;

/**
 * Entree de updateTransaction : les seuls champs qu'une partie peut pousser. Les colonnes
 * d'argent et de paiement en sont absentes, et le poids n'est retenu que si la transition
 * demandee prevoit une retarification.
 */
public record UpdateTransactionInput(
        String sellerStatus,
        String buyerStatus,
        BigDecimal weight,
        Boolean sellerReview,
        Boolean buyerReview) {

    public Transaction toTransaction() {
        Transaction tx = new Transaction();
        tx.setSellerStatus(sellerStatus);
        tx.setBuyerStatus(buyerStatus);
        tx.setWeight(weight);
        tx.setSellerReview(sellerReview);
        tx.setBuyerReview(buyerReview);
        return tx;
    }
}

package com.bagbuddy.reviewservice.dto;

import com.bagbuddy.reviewservice.model.Review;

/**
 * Entree de createReview. Ni reviewerId ni revieweeId : l'auteur vient du jeton et le sujet est
 * deduit de la transaction. Le schema interdit donc de noter un membre arbitraire.
 */
public record CreateReviewInput(Long transactionId, Integer rating, String comment) {

    public Review toReview() {
        Review review = new Review();
        review.setTransactionId(transactionId);
        review.setRating(rating);
        review.setComment(comment);
        return review;
    }
}

package com.bagbuddy.reviewservice.dto;

import com.bagbuddy.reviewservice.model.Review;

/** Seul le contenu d'un avis est modifiable ; qui note qui est fige a la creation. */
public record UpdateReviewInput(Integer rating, String comment) {

    public Review toReview() {
        Review review = new Review();
        review.setRating(rating);
        review.setComment(comment);
        return review;
    }
}

package com.bagbuddy.reviewservice.controller;

import com.bagbuddy.reviewservice.dto.CreateReviewInput;
import com.bagbuddy.reviewservice.dto.UpdateReviewInput;
import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.service.ReviewService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Surface publique de reviewservice. Les regles d'ecriture restent dans ReviewService. */
@Controller
public class ReviewGraphQlController {

    private final ReviewService reviewService;

    public ReviewGraphQlController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @QueryMapping
    public List<Review> reviews(@Argument Integer limit, @Argument Integer offset) {
        return reviewService.getAll(limit, offset);
    }

    @QueryMapping
    public Review review(@Argument Long id) {
        return reviewService.getOne(id);
    }

    @QueryMapping
    public List<Review> reviewsByReviewee(@Argument String revieweeId) {
        return reviewService.byReviewee(revieweeId);
    }

    @QueryMapping
    public List<Review> reviewsByReviewer(@Argument String reviewerId) {
        return reviewService.byReviewer(reviewerId);
    }

    @QueryMapping
    public List<Review> reviewsByTransaction(@Argument Long transactionId) {
        return reviewService.byTransaction(transactionId);
    }

    @QueryMapping
    public Double averageRating(@Argument String revieweeId) {
        return reviewService.averageForReviewee(revieweeId);
    }

    @MutationMapping
    public Review createReview(@Argument CreateReviewInput input, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.create(input.toReview(), jwt);
    }

    @MutationMapping
    public Review updateReview(@Argument Long id,
                               @Argument UpdateReviewInput input,
                               @AuthenticationPrincipal Jwt jwt) {
        return reviewService.update(id, input.toReview(), jwt.getSubject());
    }

    @MutationMapping
    public boolean deleteReview(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        reviewService.delete(id, jwt.getSubject());
        return true;
    }
}

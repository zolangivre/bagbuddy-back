package com.bagbuddy.reviewservice.service;

import com.bagbuddy.reviewservice.client.TransactionClient;
import com.bagbuddy.reviewservice.client.TransactionSnapshot;
import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.repository.ReviewRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private TransactionClient transactionClient;

    public List<Review> getAll() {
        return reviewRepository.findAll();
    }

    public Review getOne(Long id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Review not found with id " + id));
    }

    public List<Review> byReviewee(String revieweeId) {
        return reviewRepository.findByRevieweeId(revieweeId);
    }

    public List<Review> byReviewer(String reviewerId) {
        return reviewRepository.findByReviewerId(reviewerId);
    }

    public List<Review> byTransaction(Long transactionId) {
        return reviewRepository.findByTransactionId(transactionId);
    }

    public Double averageForReviewee(String revieweeId) {
        return reviewRepository.averageRatingForReviewee(revieweeId);
    }

    /**
     * A review can only be written by a party to the transaction, about the other party.
     * Author and subject are both derived server-side, so a client cannot rate an arbitrary
     * user or sign a review with somebody else's id.
     */
    @Transactional
    public Review create(Review body, Jwt caller) {
        String reviewerId = caller.getSubject();

        if (body.getTransactionId() == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        TransactionSnapshot tx = transactionClient.fetchAsCaller(body.getTransactionId(), caller.getTokenValue());
        String counterpart = tx == null ? null : tx.counterpartOf(reviewerId);
        if (counterpart == null) {
            throw new AccessDeniedException("Caller is not a party to transaction " + body.getTransactionId());
        }
        if (reviewRepository.existsByReviewerIdAndTransactionId(reviewerId, body.getTransactionId())) {
            throw new IllegalArgumentException("This transaction has already been reviewed");
        }

        Review review = new Review();
        review.setTransactionId(body.getTransactionId());
        review.setReviewerId(reviewerId);
        review.setReviewerName(caller.getClaimAsString("name"));
        review.setRevieweeId(counterpart);
        review.setRevieweeName(tx.counterpartNameOf(reviewerId));
        review.setRating(validRating(body.getRating()));
        review.setComment(body.getComment());
        return reviewRepository.save(review);
    }

    /** Only the author may edit, and only the content -- never who it is about. */
    @Transactional
    public Review update(Long id, Review body, String callerSub) {
        Review existing = getOne(id);
        requireAuthor(existing, callerSub);

        existing.setRating(validRating(body.getRating()));
        existing.setComment(body.getComment());
        return reviewRepository.save(existing);
    }

    @Transactional
    public void delete(Long id, String callerSub) {
        Review existing = getOne(id);
        requireAuthor(existing, callerSub);
        reviewRepository.delete(existing);
    }

    private void requireAuthor(Review review, String callerSub) {
        if (review.getReviewerId() == null || !review.getReviewerId().equals(callerSub)) {
            throw new AccessDeniedException("Only the author may modify review " + review.getId());
        }
    }

    private Integer validRating(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        return rating;
    }
}

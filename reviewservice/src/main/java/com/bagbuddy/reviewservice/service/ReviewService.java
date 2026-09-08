package com.bagbuddy.reviewservice.service;

import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    public List<Review> getAll() {
        return reviewRepository.findAll();
    }

    public Review getOne(Long id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Review not found with id " + id));
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

    @Transactional
    public Review create(Review review) {
        return reviewRepository.save(review);
    }

    @Transactional
    public Review update(Long id, Review body) {
        Review existing = getOne(id);

        existing.setReviewerId(body.getReviewerId());
        existing.setRevieweeId(body.getRevieweeId());
        existing.setTransactionId(body.getTransactionId());
        existing.setRating(body.getRating());
        existing.setComment(body.getComment());

        return reviewRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!reviewRepository.existsById(id)) {
            throw new RuntimeException("Review not found with id " + id);
        }
        reviewRepository.deleteById(id);
    }
}
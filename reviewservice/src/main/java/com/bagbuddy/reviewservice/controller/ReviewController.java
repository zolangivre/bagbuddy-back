package com.bagbuddy.reviewservice.controller;

import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.service.ReviewService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RestController
@RequestMapping("/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @GetMapping
    public List<Review> getAll() {
        return reviewService.getAll();
    }

    @GetMapping("/{id}")
    public Review getOne(@PathVariable Long id) {
        return reviewService.getOne(id);
    }

    @GetMapping("/reviewee/{revieweeId}")
    public List<Review> byReviewee(@PathVariable String revieweeId) {
        return reviewService.byReviewee(revieweeId);
    }

    @GetMapping("/reviewer/{reviewerId}")
    public List<Review> byReviewer(@PathVariable String reviewerId) {
        return reviewService.byReviewer(reviewerId);
    }

    @GetMapping("/transaction/{transactionId}")
    public List<Review> byTransaction(@PathVariable Long transactionId) {
        return reviewService.byTransaction(transactionId);
    }

    @GetMapping("/reviewee/{revieweeId}/average")
    public Double averageForReviewee(@PathVariable String revieweeId) {
        return reviewService.averageForReviewee(revieweeId);
    }

    @PostMapping
    public Review create(@RequestBody Review review) {
        return reviewService.create(review);
    }

    @PutMapping("/{id}")
    public Review update(@PathVariable Long id, @RequestBody Review body) {
        return reviewService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        reviewService.delete(id);
    }
}
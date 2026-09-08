package com.bagbuddy.reviewservice.repository;

import com.bagbuddy.reviewservice.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByRevieweeId(String revieweeId);
    List<Review> findByReviewerId(String reviewerId);
    List<Review> findByTransactionId(Long transactionId);
    @Query("select avg(r.rating) from Review r where r.revieweeId = ?1")
    Double averageRatingForReviewee(String revieweeId);
}

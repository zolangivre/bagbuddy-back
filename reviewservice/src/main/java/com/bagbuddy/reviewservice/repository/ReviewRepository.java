package com.bagbuddy.reviewservice.repository;

import com.bagbuddy.reviewservice.model.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByRevieweeIdOrderByCreatedAtDescIdDesc(String revieweeId, Pageable pageable);
    List<Review> findByReviewerIdOrderByCreatedAtDescIdDesc(String reviewerId, Pageable pageable);
    List<Review> findByTransactionId(Long transactionId);
    boolean existsByReviewerIdAndTransactionId(String reviewerId, Long transactionId);
    @Query("select avg(r.rating) from Review r where r.revieweeId = ?1")
    Double averageRatingForReviewee(String revieweeId);
}

package com.bagbuddy.reviewservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
// La contrainte est posee par Flyway (V4) ; la declarer ici la donne aussi au schema H2 des tests.
@Table(name = "review", uniqueConstraints = @UniqueConstraint(
        name = "uk_review_reviewer_transaction", columnNames = {"reviewer_id", "transaction_id"}))
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Qui évalue qui ?
    private String reviewerId;   // auteur de la review
    private String reviewerName;
    private String revieweeId;   // personne évaluée (ex: sellerId ou userId)
    private String revieweeName;

    private Long transactionId;   // nullable

    // Contenu de la review
    private Integer rating;       // 1..5

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

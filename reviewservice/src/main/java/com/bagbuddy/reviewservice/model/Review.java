package com.bagbuddy.reviewservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "review")
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

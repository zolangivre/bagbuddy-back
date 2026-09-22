package com.bagbuddy.transactionservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** A message between the two parties of a transaction. */
@Data
@Entity
@Table(name = "transaction_message")
public class TransactionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "sender_sub", nullable = false)
    private String senderSub;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

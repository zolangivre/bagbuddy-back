package com.bagbuddy.tripservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Le poids qu'une transaction a pris sur une annonce. Identifiee par la transaction : c'est ce
 * qui rend reserve() rejouable sans double decompte, et release() possible a l'annulation.
 */
@Data
@Entity
@Table(name = "trip_reservation")
public class TripReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tripId;

    @Column(nullable = false, unique = true)
    private Long transactionId;

    @Column(nullable = false)
    private BigDecimal weight;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Nul tant que le poids est pris sur l'annonce. */
    private LocalDateTime releasedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public boolean isActive() {
        return releasedAt == null;
    }
}

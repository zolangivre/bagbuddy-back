package com.bagbuddy.tripservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A member's standing search: they are emailed when a matching listing is published. */
@Data
@Entity
@Table(name = "trip_alert")
public class TripAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sub;

    /** Taken from the token when the alert was created. */
    @Column(nullable = false)
    private String email;

    /** en or fr: the language of the email. */
    @Column(nullable = false, length = 2)
    private String language;

    @Column(name = "departure_airport", nullable = false, length = 3)
    private String departureAirport;

    @Column(name = "arrival_airport", nullable = false, length = 3)
    private String arrivalAirport;

    /** Optional: without it, any date matches. */
    @Column(name = "departure_day")
    private LocalDate departureDay;

    @Column(name = "flex_days", nullable = false)
    private int flexDays;

    @Column(name = "max_price_per_kg", precision = 10, scale = 2)
    private BigDecimal maxPricePerKg;

    @Column(name = "min_weight", precision = 10, scale = 2)
    private BigDecimal minWeight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

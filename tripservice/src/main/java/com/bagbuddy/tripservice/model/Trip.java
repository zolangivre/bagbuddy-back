package com.bagbuddy.tripservice.model;

import com.bagbuddy.tripservice.config.TripListener;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@EntityListeners(TripListener.class)
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private UserInfo userInfo;

    private String userId;

    private String stripeAccountId;

    private String departureAirport;
    private String arrivalAirport;

    private LocalDateTime departureDate;
    private LocalDateTime arrivalDate;

    private BigDecimal totalWeightAvailable;
    private BigDecimal remainingWeight;
    private BigDecimal pricePerKg;

    private Boolean active;

    @Column(columnDefinition = "TEXT")
    private String conditions;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
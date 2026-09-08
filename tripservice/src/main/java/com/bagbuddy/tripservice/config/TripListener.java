package com.bagbuddy.tripservice.config;

import com.bagbuddy.tripservice.model.Trip;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TripListener {

    @PrePersist
    @PreUpdate
    public void updateActiveStatus(Trip trip) {
        if (trip.getCreatedAt() == null) {
            trip.setCreatedAt(LocalDateTime.now());
        }
        trip.setActive(
                trip.getRemainingWeight() != null &&
                        trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0 &&
                        trip.getDepartureDate() != null &&
                        trip.getDepartureDate().isAfter(LocalDateTime.now())
        );
    }
}
package com.bagbuddy.tripservice.dto;

import com.bagbuddy.tripservice.model.TripAlert;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Alerte telle qu'exposee a son proprietaire. L'email n'y figure pas : il est deja le sien. */
public record TripAlertView(Long id, String departureAirport, String arrivalAirport, String date, int flexDays,
                            BigDecimal maxPricePerKg, BigDecimal minWeight, LocalDateTime createdAt) {

    public static TripAlertView of(TripAlert alert) {
        return new TripAlertView(alert.getId(), alert.getDepartureAirport(), alert.getArrivalAirport(),
                alert.getDepartureDay() == null ? null : alert.getDepartureDay().toString(), alert.getFlexDays(),
                alert.getMaxPricePerKg(), alert.getMinWeight(), alert.getCreatedAt());
    }

    public static List<TripAlertView> of(List<TripAlert> alerts) {
        return alerts.stream().map(TripAlertView::of).toList();
    }
}

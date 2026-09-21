package com.bagbuddy.tripservice.dto;

import java.math.BigDecimal;

/** Entree de createTripAlert. Ni sub ni email : ils viennent du jeton. */
public record TripAlertInput(String departureAirport, String arrivalAirport, String date, Integer flexDays,
                             BigDecimal maxPricePerKg, BigDecimal minWeight, String language) {
}

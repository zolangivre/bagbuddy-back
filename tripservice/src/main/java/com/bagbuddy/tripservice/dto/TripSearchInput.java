package com.bagbuddy.tripservice.dto;

import java.math.BigDecimal;

/**
 * Filtre de searchTrips. Reprend la semantique des filtres du front (ListingFilters et
 * departsAround) pour que basculer le filtrage cote serveur ne change aucun resultat.
 *
 * date est un jour calendaire "YYYY-MM-DD" : departure_date est une heure locale d'aeroport, et
 * c'est son jour qui compte, pas un instant converti dans le fuseau du visiteur.
 */
public record TripSearchInput(
        String departureAirport,
        String arrivalAirport,
        String date,
        Integer flexDays,
        BigDecimal minPricePerKg,
        BigDecimal maxPricePerKg,
        BigDecimal minWeight,
        BigDecimal maxWeight,
        TripSort sort) {
}

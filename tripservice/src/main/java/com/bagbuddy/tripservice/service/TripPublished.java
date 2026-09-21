package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.model.Trip;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Une annonce vient d'etre publiee. Publie dans la transaction d'ecriture de createTrip, traite
 * seulement une fois celle-ci validee (voir {@link TripAlertNotifier}).
 *
 * Tout ce dont la recherche d'alertes et l'email ont besoin est copie ici plutot que relu depuis
 * l'entite : l'ecouteur tourne sur un autre thread, une fois le contexte de persistance disparu.
 *
 * @param authorSub l'auteur de l'annonce, le seul membre que ses propres alertes ne notifient pas
 */
public record TripPublished(
        Long tripId,
        String authorSub,
        String departureAirport,
        String arrivalAirport,
        LocalDateTime departureDate,
        BigDecimal pricePerKg,
        BigDecimal remainingWeight) {

    public static TripPublished of(Trip trip) {
        return new TripPublished(
                trip.getId(),
                trip.getUserId(),
                trip.getDepartureAirport(),
                trip.getArrivalAirport(),
                trip.getDepartureDate(),
                trip.getPricePerKg(),
                trip.getRemainingWeight());
    }
}

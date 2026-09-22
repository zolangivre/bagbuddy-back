package com.bagbuddy.tripservice.dto;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.UserInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entree de createTrip / updateTrip. Ce que le schema n'expose pas ne peut pas etre ecrit :
 * ni userId, ni l'identite de l'instantane (elle vient du jeton), ni active (calcule par
 * TripListener), ni remainingWeight (l'inventaire est decide par le serveur : voir TripService). C'est la meme regle qu'en REST, mais rendue explicite par le type.
 */
public record TripInput(
        String departureAirport,
        String arrivalAirport,
        LocalDateTime departureDate,
        LocalDateTime arrivalDate,
        BigDecimal totalWeightAvailable,
        BigDecimal pricePerKg,
        String conditions,
        String stripeAccountId,
        ProfileInput profile) {

    /** Champs libres du profil, les seuls que l'appelant fournit lui-meme. */
    public record ProfileInput(String bio, String location, String phone) {
    }

    /** Projette l'entree sur une entite detachee, telle que TripService l'attend. */
    public Trip toTrip() {
        Trip trip = new Trip();
        trip.setDepartureAirport(departureAirport);
        trip.setArrivalAirport(arrivalAirport);
        trip.setDepartureDate(departureDate);
        trip.setArrivalDate(arrivalDate);
        trip.setTotalWeightAvailable(totalWeightAvailable);
        trip.setPricePerKg(pricePerKg);
        trip.setConditions(conditions);
        trip.setStripeAccountId(stripeAccountId);
        if (profile != null) {
            UserInfo info = new UserInfo();
            info.setBio(profile.bio());
            info.setLocation(profile.location());
            info.setPhone(profile.phone());
            trip.setUserInfo(info);
        }
        return trip;
    }
}

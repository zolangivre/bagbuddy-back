package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.ListingInfo;

/** Instantane de l'annonce fige a la reservation. Les dates y sont du texte, comme en base. */
public record ListingInfoView(
        PartyView sellerUserInfo,
        String departureAirport,
        String arrivalAirport,
        String departureDate,
        String arrivalDate,
        double totalWeightAvailable,
        double remainingWeight,
        double pricePerKg,
        String conditions,
        String createdAt) {

    public static ListingInfoView of(ListingInfo source) {
        if (source == null) {
            return null;
        }
        return new ListingInfoView(
                PartyView.of(source.getSellerUserInfo()),
                source.getDepartureAirport(),
                source.getArrivalAirport(),
                source.getDepartureDate(),
                source.getArrivalDate(),
                source.getTotalWeightAvailable(),
                source.getRemainingWeight(),
                source.getPricePerKg(),
                source.getConditions(),
                source.getCreatedAt());
    }
}

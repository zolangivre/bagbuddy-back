package com.bagbuddy.tripservice.dto;

import com.bagbuddy.tripservice.model.Trip;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TripResponse {

    private Long id;
    private UserInfoView userInfo;
    private String userId;
    private String departureAirport;
    private String arrivalAirport;
    private LocalDateTime departureDate;
    private LocalDateTime arrivalDate;
    private BigDecimal totalWeightAvailable;
    private BigDecimal remainingWeight;
    private BigDecimal pricePerKg;
    private Boolean active;
    private String conditions;
    private LocalDateTime createdAt;

    // Payout account: owner only.
    private String stripeAccountId;

    public static TripResponse of(Trip trip, String callerSub) {
        boolean owner = trip.getUserId() != null && trip.getUserId().equals(callerSub);
        TripResponse dto = new TripResponse();
        dto.id = trip.getId();
        dto.userInfo = UserInfoView.of(trip.getUserInfo(), owner);
        dto.userId = trip.getUserId();
        dto.departureAirport = trip.getDepartureAirport();
        dto.arrivalAirport = trip.getArrivalAirport();
        dto.departureDate = trip.getDepartureDate();
        dto.arrivalDate = trip.getArrivalDate();
        dto.totalWeightAvailable = trip.getTotalWeightAvailable();
        dto.remainingWeight = trip.getRemainingWeight();
        dto.pricePerKg = trip.getPricePerKg();
        dto.active = trip.getActive();
        dto.conditions = trip.getConditions();
        dto.createdAt = trip.getCreatedAt();
        if (owner) {
            dto.stripeAccountId = trip.getStripeAccountId();
        }
        return dto;
    }

    public static List<TripResponse> of(List<Trip> trips, String callerSub) {
        return trips.stream().map(trip -> of(trip, callerSub)).toList();
    }
}

package com.bagbuddy.tripservice.controller;

import com.bagbuddy.tripservice.dto.TripResponse;
import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.security.CallerIdentity;
import com.bagbuddy.tripservice.service.TripService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/trips")
public class TripController {

    @Autowired
    private TripService tripService;

    @GetMapping
    public List<TripResponse> getAllTrips(@AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getAllTrips(), CallerIdentity.subOf(jwt));
    }

    @GetMapping("/{id}")
    public TripResponse getTripById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getTripById(id), CallerIdentity.subOf(jwt));
    }

    @GetMapping("/user/{userId}")
    public List<TripResponse> getTripsByUserId(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getTripsByUserId(userId), CallerIdentity.subOf(jwt));
    }

    @PostMapping
    public TripResponse createTrip(@RequestBody Trip trip, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.createTrip(trip, jwt), CallerIdentity.subOf(jwt));
    }

    @PutMapping("/{id}")
    public TripResponse updateTrip(@PathVariable Long id,
                                   @RequestBody Trip tripDetails,
                                   @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TripResponse.of(tripService.updateTrip(id, tripDetails, caller), caller);
    }

    @DeleteMapping("/{id}")
    public void deleteTrip(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        tripService.deleteTrip(id, CallerIdentity.subOf(jwt));
    }

    @GetMapping("/active")
    public List<TripResponse> getActiveTrips(@AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getActiveTrips(), CallerIdentity.subOf(jwt));
    }

    @GetMapping("/inactive")
    public List<TripResponse> getInactiveTrips(@AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getInactiveTrips(), CallerIdentity.subOf(jwt));
    }

    /**
     * Payout account of a traveller. Restricted to the traveller themselves: the payment
     * flow no longer needs the seller's Stripe account on the client side, stripeservice
     * resolves it server-side.
     */
    @GetMapping("/user/{userId}/stripe-account")
    public Map<String, String> getStripeAccountIdByUser(@PathVariable String userId,
                                                        @AuthenticationPrincipal Jwt jwt) {
        if (!userId.equals(CallerIdentity.subOf(jwt))) {
            throw new AccessDeniedException("Stripe account is only readable by its owner");
        }
        String stripeAccountId = tripService.getStripeAccountIdByUser(userId);
        return Map.of("stripeAccountId", stripeAccountId != null ? stripeAccountId : "");
    }

    /**
     * Full listing, including the seller's contact snapshot. Reserved for service-to-service
     * calls (transactionservice prices a booking against the real listing) and gated on the
     * 'service' realm role in SecurityConfig -- never reachable with a user token.
     */
    @GetMapping("/internal/{id}")
    public Trip getTripInternal(@PathVariable Long id) {
        return tripService.getTripById(id);
    }

    /**
     * Reserves capacity on a listing and returns it as it now stands. Service role only.
     * The returned snapshot is what transactionservice prices the booking against, so the
     * capacity check and the price come from the same locked read.
     */
    @PostMapping("/internal/{id}/reserve")
    public Trip reserveCapacity(@PathVariable Long id, @RequestBody ReserveRequest body) {
        return tripService.reserveCapacity(id, body.weight());
    }

    public record ReserveRequest(java.math.BigDecimal weight) {
    }
}

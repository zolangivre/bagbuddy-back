package com.bagbuddy.tripservice.controller;

import com.bagbuddy.tripservice.dto.TripInput;
import com.bagbuddy.tripservice.dto.TripResponse;
import com.bagbuddy.tripservice.security.CallerIdentity;
import com.bagbuddy.tripservice.service.TripService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Surface publique de tripservice. Les resolvers restent fins : la logique et surtout les
 * regles de propriete vivent dans TripService, exactement comme du temps des controleurs REST.
 *
 * Le sub de l'appelant vient toujours du jeton verifie, jamais des arguments.
 */
@Controller
public class TripGraphQlController {

    private final TripService tripService;

    public TripGraphQlController(TripService tripService) {
        this.tripService = tripService;
    }

    @QueryMapping
    public List<TripResponse> trips(@Argument Integer limit, @Argument Integer offset,
                                        @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getAllTrips(limit, offset), CallerIdentity.subOf(jwt));
    }

    @QueryMapping
    public List<TripResponse> activeTrips(@Argument Integer limit, @Argument Integer offset,
                                        @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getActiveTrips(limit, offset), CallerIdentity.subOf(jwt));
    }

    @QueryMapping
    public List<TripResponse> inactiveTrips(@Argument Integer limit, @Argument Integer offset,
                                        @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getInactiveTrips(limit, offset), CallerIdentity.subOf(jwt));
    }

    @QueryMapping
    public TripResponse trip(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getTripById(id), CallerIdentity.subOf(jwt));
    }

    @QueryMapping
    public List<TripResponse> tripsByUser(@Argument String userId, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.getTripsByUserId(userId), CallerIdentity.subOf(jwt));
    }

    @QueryMapping
    public String payoutAccount(@Argument String userId, @AuthenticationPrincipal Jwt jwt) {
        if (!userId.equals(CallerIdentity.subOf(jwt))) {
            throw new AccessDeniedException("Stripe account is only readable by its owner");
        }
        return tripService.getStripeAccountIdByUser(userId);
    }

    @MutationMapping
    public TripResponse createTrip(@Argument TripInput input, @AuthenticationPrincipal Jwt jwt) {
        return TripResponse.of(tripService.createTrip(input.toTrip(), jwt), CallerIdentity.subOf(jwt));
    }

    @MutationMapping
    public TripResponse updateTrip(@Argument Long id,
                                   @Argument TripInput input,
                                   @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TripResponse.of(tripService.updateTrip(id, input.toTrip(), caller), caller);
    }

    @MutationMapping
    public boolean deleteTrip(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        tripService.deleteTrip(id, CallerIdentity.subOf(jwt));
        return true;
    }
}

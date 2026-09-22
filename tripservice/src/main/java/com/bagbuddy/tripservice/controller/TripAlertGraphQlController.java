package com.bagbuddy.tripservice.controller;

import com.bagbuddy.tripservice.dto.TripAlertInput;
import com.bagbuddy.tripservice.dto.TripAlertView;
import com.bagbuddy.tripservice.security.CallerIdentity;
import com.bagbuddy.tripservice.service.TripAlertService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Alertes de trajet de l'appelant. Aucune operation ne prend de sub : tout vient du jeton. */
@Controller
public class TripAlertGraphQlController {

    private final TripAlertService alerts;

    public TripAlertGraphQlController(TripAlertService alerts) {
        this.alerts = alerts;
    }

    @QueryMapping
    public List<TripAlertView> myTripAlerts(@AuthenticationPrincipal Jwt jwt) {
        return TripAlertView.of(alerts.mine(CallerIdentity.subOf(jwt)));
    }

    @MutationMapping
    public TripAlertView createTripAlert(@Argument TripAlertInput input, @AuthenticationPrincipal Jwt jwt) {
        return TripAlertView.of(alerts.create(jwt, input));
    }

    @MutationMapping
    public boolean deleteTripAlert(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        return alerts.delete(id, CallerIdentity.subOf(jwt));
    }
}

package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.dto.ReportMemberRequest;
import com.bagbuddy.userservice.service.FavoriteService;
import com.bagbuddy.userservice.service.ReportService;

import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Favorites and reports, on the same /users/graphql as the account operations.
 *
 * Same security rule as UserGraphQlController, since the endpoint is open for sign-up:
 * <strong>every operation here carries @PreAuthorize</strong>. All of them act on the caller's
 * own sub; none takes a member id to act as.
 */
@Controller
@Validated
public class CommunityGraphQlController {

    private final FavoriteService favorites;
    private final ReportService reports;

    public CommunityGraphQlController(FavoriteService favorites, ReportService reports) {
        this.favorites = favorites;
        this.reports = reports;
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<Long> favoriteListingIds(@AuthenticationPrincipal Jwt jwt) {
        return favorites.listingIds(jwt.getSubject());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean addFavoriteListing(@Argument Long listingId, @AuthenticationPrincipal Jwt jwt) {
        return favorites.add(jwt.getSubject(), listingId);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean removeFavoriteListing(@Argument Long listingId, @AuthenticationPrincipal Jwt jwt) {
        return favorites.remove(jwt.getSubject(), listingId);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean reportMember(@Argument @Valid ReportMemberRequest input,
                                @AuthenticationPrincipal Jwt jwt) {
        return reports.report(jwt.getSubject(), jwt.getClaimAsString("email"), input);
    }
}

package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le compte Stripe Connect d'un membre, pour stripeservice seul (role realm 'service', voir
 * SecurityConfig ; Caddy renvoie 404 sur /users/internal en production). En REST comme les autres
 * endpoints service-a-service : il ne doit pas exister sur le schema public, dont le point
 * d'entree est ouvert sans jeton.
 *
 * Contrairement aux operations GraphQL, celles-ci prennent un sub en parametre : c'est un service
 * qui agit pour un membre, pas le membre lui-meme.
 */
@RestController
@RequestMapping("/users/internal")
public class UserInternalController {

    private final UserService userService;

    public UserInternalController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{sub}/payout-account")
    public PayoutAccount payoutAccount(@PathVariable String sub) {
        return new PayoutAccount(userService.payoutAccount(sub).orElse(null));
    }

    @PutMapping("/{sub}/payout-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void savePayoutAccount(@PathVariable String sub, @RequestBody PayoutAccount body) {
        userService.savePayoutAccount(sub, body.stripeAccountId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    public record PayoutAccount(String stripeAccountId) {
    }
}

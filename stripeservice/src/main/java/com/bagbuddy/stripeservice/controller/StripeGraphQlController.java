package com.bagbuddy.stripeservice.controller;

import com.bagbuddy.stripeservice.service.PayoutService;
import com.bagbuddy.stripeservice.service.StripeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * Surface publique de stripeservice. Le montant n'apparait nulle part dans le schema : rien de
 * ce qui touche a l'argent ne vient du client, seulement l'identifiant de la transaction payee.
 */
@Controller
public class StripeGraphQlController {

    private final StripeService stripeService;
    private final PayoutService payoutService;

    @Value("${STRIPE_PUBLISHABLE_KEY}")
    private String publishableKey;

    public StripeGraphQlController(StripeService stripeService, PayoutService payoutService) {
        this.stripeService = stripeService;
        this.payoutService = payoutService;
    }

    @QueryMapping
    public StripeConfig stripeConfig() {
        return new StripeConfig(publishableKey);
    }

    @MutationMapping
    public PaymentIntentPayload createPaymentIntent(@Argument Long transactionId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return new PaymentIntentPayload(stripeService.createPaymentIntent(transactionId, jwt));
    }

    @QueryMapping
    public PayoutService.AccountStatus payoutAccount(@AuthenticationPrincipal Jwt jwt) {
        return payoutService.accountStatus(jwt);
    }

    @MutationMapping
    public PayoutOnboarding startPayoutOnboarding(@AuthenticationPrincipal Jwt jwt) {
        return new PayoutOnboarding(payoutService.startOnboarding(jwt));
    }

    public record PayoutOnboarding(String url) {
    }

    public record StripeConfig(String publishableKey) {
    }

    public record PaymentIntentPayload(String clientSecret) {
    }
}

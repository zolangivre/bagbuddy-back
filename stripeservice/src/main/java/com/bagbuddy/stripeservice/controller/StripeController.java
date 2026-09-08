package com.bagbuddy.stripeservice.controller;

import com.bagbuddy.stripeservice.service.StripeService;
import com.stripe.model.PaymentIntent;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/stripe")
public class StripeController {

    private final StripeService stripeService;

    @Value("${STRIPE_PUBLISHABLE_KEY}")
    private String publishableKey;

    public StripeController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        return ResponseEntity.ok(Map.of("publishableKey", publishableKey));
    }

    /**
     * Takes the transaction being paid, not an amount: the charge is priced server-side.
     */
    @PostMapping("/create-payment-intent")
    public ResponseEntity<CreatePaymentIntentResponse> createPaymentIntent(
            @RequestBody CreatePaymentIntentRequest req,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        PaymentIntent pi = stripeService.createPaymentIntent(req.getTransactionId(), jwt);
        return ResponseEntity.ok(new CreatePaymentIntentResponse(pi.getClientSecret()));
    }

    /**
     * Authenticated by the Stripe signature rather than a bearer token (see SecurityConfig).
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String payload,
                                        @RequestHeader("Stripe-Signature") String signature) {
        stripeService.handleWebhook(payload, signature);
        return ResponseEntity.ok().build();
    }

    @Data
    public static class CreatePaymentIntentRequest {
        private Long transactionId;
    }

    @Data
    public static class CreatePaymentIntentResponse {
        private final String clientSecret;
    }
}

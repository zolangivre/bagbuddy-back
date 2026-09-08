package com.bagbuddy.stripeservice.controller;

import com.bagbuddy.stripeservice.service.StripeService;
import com.stripe.model.PaymentIntent;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/create-payment-intent")
    public ResponseEntity<CreatePaymentIntentResponse> createPaymentIntent(@RequestBody CreatePaymentIntentRequest req) throws Exception {
        PaymentIntent pi = stripeService.createPaymentIntent(req.getAmount(), req.getCurrency(), req.getMetadata());
        return ResponseEntity.ok(new CreatePaymentIntentResponse(pi.getClientSecret()));
    }

    @Data
    public static class CreatePaymentIntentRequest {
        private Long amount;
        private String currency = "eur";
        private Map<String, String> metadata;
    }

    @Data
    public static class CreatePaymentIntentResponse {
        private final String clientSecret;
    }
}
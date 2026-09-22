package com.bagbuddy.stripeservice.controller;

import com.bagbuddy.stripeservice.service.StripeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seul chemin qui marque une transaction payee, et le seul reste en REST du service : c'est
 * Stripe qui appelle, avec le corps brut necessaire a la verification de signature. GraphQL
 * n'a rien a apporter a un webhook -- l'appelant ne choisit pas ses champs.
 *
 * Authentifie par Webhook.constructEvent contre STRIPE_WEBHOOK_SECRET, pas par un jeton.
 */
@RestController
public class StripeWebhookController {

    private final StripeService stripeService;

    public StripeWebhookController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    @PostMapping("/stripe/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String payload,
                                        @RequestHeader("Stripe-Signature") String signature) {
        stripeService.handleWebhook(payload, signature);
        return ResponseEntity.ok().build();
    }
}

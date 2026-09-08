package com.bagbuddy.stripeservice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.stripe.Stripe;

@Component
public class StripeConfig {

    @Value("${STRIPE_SECRET_KEY}")
    private String secretKey;

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is not set!");
        }
        Stripe.apiKey = secretKey;
    }
}
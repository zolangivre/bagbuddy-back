package com.bagbuddy.stripeservice.service;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class StripeService {

    public PaymentIntent createPaymentIntent(Long amount, String currency, Map<String, String> metadata) throws Exception {
        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(currency)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                );

        if (metadata != null && !metadata.isEmpty()) {
            metadata.forEach((k,v) -> builder.putMetadata(k, v));
        }

        PaymentIntentCreateParams params = builder.build();
        return PaymentIntent.create(params);
    }
}
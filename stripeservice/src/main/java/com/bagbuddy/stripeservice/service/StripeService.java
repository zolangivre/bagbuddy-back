package com.bagbuddy.stripeservice.service;

import com.bagbuddy.stripeservice.client.TransactionClient;
import com.bagbuddy.stripeservice.client.TransactionSnapshot;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StripeService {

    private final TransactionClient transactionClient;
    private final String currency;
    private final String webhookSecret;

    public StripeService(TransactionClient transactionClient,
                         @Value("${bagbuddy.stripe.currency:eur}") String currency,
                         @Value("${STRIPE_WEBHOOK_SECRET}") String webhookSecret) {
        this.transactionClient = transactionClient;
        this.currency = currency;
        this.webhookSecret = webhookSecret;
    }

    /**
     * The amount is derived from the transaction as transactionservice stored it -- which is
     * itself priced against the listing. Nothing about the charge comes from the client
     * beyond the id of the transaction being paid.
     */
    public PaymentIntent createPaymentIntent(Long transactionId, Jwt caller) throws Exception {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        TransactionSnapshot tx = transactionClient.fetchAsCaller(transactionId, caller.getTokenValue());
        if (tx == null || tx.getTotal() == null) {
            throw new IllegalArgumentException("Transaction " + transactionId + " has no amount to charge");
        }
        if (!caller.getSubject().equals(tx.getBuyerId())) {
            throw new AccessDeniedException("Only the buyer may pay for transaction " + transactionId);
        }
        if (tx.getPaidAt() != null) {
            throw new IllegalArgumentException("Transaction " + transactionId + " is already paid");
        }

        long amountInMinorUnits = tx.getTotal()
                .setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();
        if (amountInMinorUnits <= 0) {
            throw new IllegalArgumentException("Transaction " + transactionId + " has a non-positive amount");
        }

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInMinorUnits)
                .setCurrency(currency)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                // Metadata is built server-side; the client cannot inject arbitrary keys.
                .putMetadata("transactionId", String.valueOf(tx.getId()))
                .putMetadata("buyerId", tx.getBuyerId())
                .putMetadata("sellerId", tx.getSellerId())
                .build();

        return PaymentIntent.create(params);
    }

    /**
     * Payment confirmation is only ever accepted from Stripe, authenticated by the webhook
     * signature -- never from the browser telling us it paid.
     */
    public void handleWebhook(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new AccessDeniedException("Invalid Stripe signature");
        }

        if (!"payment_intent.succeeded".equals(event.getType())) {
            return;
        }
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalArgumentException("Unreadable payment_intent payload"));

        String transactionId = intent.getMetadata() == null ? null : intent.getMetadata().get("transactionId");
        if (transactionId == null) {
            return;
        }
        transactionClient.confirmPayment(
                Long.valueOf(transactionId),
                intent.getId(),
                intent.getAmount(),
                intent.getCurrency());
    }
}

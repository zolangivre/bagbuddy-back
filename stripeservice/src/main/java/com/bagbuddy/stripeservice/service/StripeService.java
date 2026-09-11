package com.bagbuddy.stripeservice.service;

import com.bagbuddy.stripeservice.client.TransactionClient;
import com.bagbuddy.stripeservice.client.TransactionSnapshot;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    private final TransactionClient transactionClient;
    private final String currency;
    private final String webhookSecret;
    private final String awaitingPaymentStatus;
    private final String paymentRequiredStatus;
    private final MeterRegistry meters;

    public StripeService(TransactionClient transactionClient,
                         MeterRegistry meters,
                         @Value("${bagbuddy.stripe.currency:eur}") String currency,
                         @Value("${STRIPE_WEBHOOK_SECRET}") String webhookSecret,
                         // Same keys as transactionservice's TransactionStatusProperties, so an
                         // environment that renames a status renames it for both services.
                         @Value("${bagbuddy.transaction.status.awaiting-payment:awaiting_payment}")
                         String awaitingPaymentStatus,
                         @Value("${bagbuddy.transaction.status.payment-required:payment_required}")
                         String paymentRequiredStatus) {
        this.transactionClient = transactionClient;
        this.currency = currency;
        this.webhookSecret = webhookSecret;
        this.awaitingPaymentStatus = awaitingPaymentStatus;
        this.paymentRequiredStatus = paymentRequiredStatus;
        this.meters = meters;
    }

    /**
     * Un paiement encaisse par Stripe mais pas enregistre sur la transaction : de l'argent a rendre
     * a la main. Le log dit lequel ; ce compteur est ce sur quoi une alerte peut se declencher.
     */
    private void countUnrecordedPayment(String reason) {
        meters.counter("bagbuddy.payments.unrecorded", "reason", reason).increment();
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
        // Only once the seller has accepted: the total is final from that point on, whereas a
        // PaymentIntent created on a request could be paid after the booking has been re-priced.
        if (!awaitingPaymentStatus.equals(tx.getSellerStatus())
                || !paymentRequiredStatus.equals(tx.getBuyerStatus())) {
            throw new IllegalArgumentException("Transaction " + transactionId + " is not awaiting payment");
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
        if (!currency.equalsIgnoreCase(intent.getCurrency())) {
            log.error("PaymentIntent {} for transaction {} was paid in {} instead of {}: not recorded, "
                    + "refund it manually", intent.getId(), transactionId, intent.getCurrency(), currency);
            countUnrecordedPayment("currency");
            return;
        }
        try {
            transactionClient.confirmPayment(
                    Long.valueOf(transactionId),
                    intent.getId(),
                    intent.getAmount(),
                    intent.getCurrency());
        } catch (HttpClientErrorException.BadRequest ex) {
            // transactionservice refused the payment for good (wrong state or amount): a retry
            // from Stripe would be refused the same way, so the webhook is acknowledged. The money
            // has been taken though, which is why this is an error and not a warning.
            log.error("PaymentIntent {} for transaction {} was refused by transactionservice: not "
                    + "recorded, refund it manually ({})", intent.getId(), transactionId,
                    ex.getResponseBodyAsString());
            countUnrecordedPayment("refused");
        }
    }
}

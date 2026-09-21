package com.bagbuddy.transactionservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Demande a stripeservice d'executer les mouvements d'argent que transactionservice a decides.
 * stripeservice les rend rejouables (un remboursement ou un versement par transaction, jamais
 * deux) : c'est ce qui permet a SettlementJob de relancer sans crainte ce qui a echoue.
 */
@Component
public class StripeClient {

    private static final String CIRCUIT = "stripeservice";

    public enum TransferStatus { PAID, AWAITING_ACCOUNT }

    public record TransferResult(TransferStatus status, String transferId) {
    }

    private record RefundResult(String refundId) {
    }

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public StripeClient(RestClient.Builder builder,
                        ServiceTokenProvider tokenProvider,
                        CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                        @Value("${bagbuddy.stripe-service.url}") String stripeServiceUrl) {
        this.restClient = builder.clone().baseUrl(stripeServiceUrl).build();
        this.tokenProvider = tokenProvider;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /** @return l'identifiant du remboursement Stripe */
    public String refund(Long transactionId, String paymentIntentId, long amount) {
        return circuitBreakerFactory.create(CIRCUIT).run(() -> {
            RefundResult result = post("/stripe/internal/refunds", Map.of(
                    "transactionId", transactionId,
                    "paymentIntentId", paymentIntentId,
                    "amount", amount), RefundResult.class);
            if (result == null || result.refundId() == null) {
                throw new IllegalStateException("stripeservice returned no refund for transaction " + transactionId);
            }
            return result.refundId();
        }, CircuitFallbacks.failFast(CIRCUIT));
    }

    public TransferResult transfer(Long transactionId, String paymentIntentId, String sellerSub, long amount) {
        return circuitBreakerFactory.create(CIRCUIT).run(() -> {
            TransferResult result = post("/stripe/internal/transfers", Map.of(
                    "transactionId", transactionId,
                    "paymentIntentId", paymentIntentId,
                    "sellerSub", sellerSub,
                    "amount", amount), TransferResult.class);
            if (result == null || result.status() == null) {
                throw new IllegalStateException("stripeservice returned no transfer status for transaction " + transactionId);
            }
            return result;
        }, CircuitFallbacks.failFast(CIRCUIT));
    }

    private <T> T post(String path, Map<String, Object> body, Class<T> type) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 400) {
                // Refus definitif (montant superieur au paiement, PaymentIntent inconnu...) : le
                // rejouer n'y changera rien, il faut un humain.
                throw new IllegalArgumentException("stripeservice refused " + path + ": " + ex.getResponseBodyAsString());
            }
            throw ex;
        }
    }
}

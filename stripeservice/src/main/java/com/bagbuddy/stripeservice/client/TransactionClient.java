package com.bagbuddy.stripeservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class TransactionClient {

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public TransactionClient(RestClient.Builder builder,
                             ServiceTokenProvider tokenProvider,
                             @Value("${bagbuddy.transaction-service.url}") String transactionServiceUrl) {
        this.restClient = builder.baseUrl(transactionServiceUrl).build();
        this.tokenProvider = tokenProvider;
    }

    /** Read with the buyer's own token, so transactionservice enforces the participant check. */
    public TransactionSnapshot fetchAsCaller(Long transactionId, String callerToken) {
        try {
            return restClient.get()
                    .uri("/transactions/{id}", transactionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                    .retrieve()
                    .body(TransactionSnapshot.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AccessDeniedException("Caller is not a party to transaction " + transactionId);
            }
            if (status == 404) {
                throw new NoSuchElementException("Transaction not found: " + transactionId);
            }
            throw ex;
        }
    }

    /** Webhook path: Stripe has no user token, so this uses the service role. */
    public void confirmPayment(Long transactionId, String paymentIntentId, Long amount, String currency) {
        restClient.post()
                .uri("/transactions/internal/{id}/payment", transactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                .body(Map.of(
                        "paymentIntentId", paymentIntentId,
                        "amount", amount,
                        "currency", currency))
                .retrieve()
                .toBodilessEntity();
    }
}

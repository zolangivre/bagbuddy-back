package com.bagbuddy.reviewservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.NoSuchElementException;

/**
 * Reads a transaction using the caller's own token. transactionservice already refuses to
 * serve a transaction to somebody who is not a party to it, so a 403 here is exactly the
 * answer we want: you cannot review a deal you were not part of.
 */
@Component
public class TransactionClient {

    private final RestClient restClient;

    public TransactionClient(RestClient.Builder builder,
                             @Value("${bagbuddy.transaction-service.url}") String transactionServiceUrl) {
        this.restClient = builder.baseUrl(transactionServiceUrl).build();
    }

    public TransactionSnapshot fetchAsCaller(Long transactionId, String callerToken) {
        try {
            return restClient.get()
                    .uri("/transactions/{id}", transactionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                    .retrieve()
                    .body(TransactionSnapshot.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 403 || status == 401) {
                throw new AccessDeniedException("Caller is not a party to transaction " + transactionId);
            }
            if (status == 404) {
                throw new NoSuchElementException("Transaction not found: " + transactionId);
            }
            throw ex;
        }
    }
}

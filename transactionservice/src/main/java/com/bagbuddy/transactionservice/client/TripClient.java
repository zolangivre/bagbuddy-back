package com.bagbuddy.transactionservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class TripClient {

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public TripClient(RestClient.Builder builder,
                      ServiceTokenProvider tokenProvider,
                      @Value("${bagbuddy.trip-service.url}") String tripServiceUrl) {
        this.restClient = builder.baseUrl(tripServiceUrl).build();
        this.tokenProvider = tokenProvider;
    }

    /**
     * Reserves capacity and returns the listing as it now stands. Doing this in one call
     * means the capacity check and the price come from the same locked read on tripservice.
     */
    public TripSnapshot reserve(Long listingId, java.math.BigDecimal weight) {
        try {
            return restClient.post()
                    .uri("/trips/internal/{id}/reserve", listingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .body(Map.of("weight", weight))
                    .retrieve()
                    .body(TripSnapshot.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new NoSuchElementException("Listing not found: " + listingId);
            }
            if (ex.getStatusCode().value() == 400) {
                throw new IllegalArgumentException("Listing cannot take this booking");
            }
            throw ex;
        }
    }

    public TripSnapshot fetch(Long listingId) {
        try {
            return restClient.get()
                    .uri("/trips/internal/{id}", listingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .retrieve()
                    .body(TripSnapshot.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new NoSuchElementException("Listing not found: " + listingId);
            }
            throw ex;
        }
    }
}

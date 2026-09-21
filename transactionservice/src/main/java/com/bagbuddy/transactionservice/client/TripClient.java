package com.bagbuddy.transactionservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class TripClient {

    private static final String CIRCUIT = "tripservice";

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public TripClient(RestClient.Builder builder,
                      ServiceTokenProvider tokenProvider,
                      CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                      @Value("${bagbuddy.trip-service.url}") String tripServiceUrl) {
        this.restClient = builder.baseUrl(tripServiceUrl).build();
        this.tokenProvider = tokenProvider;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * Reserve la capacite pour une transaction et renvoie l'annonce telle qu'elle est desormais.
     * Faire les deux en un appel garantit que le controle de capacite et le prix proviennent de la
     * meme lecture verrouillee cote tripservice.
     *
     * tripservice rattache la reservation a transactionId : la rejouer ne retire pas le poids
     * deux fois. Aucun reessai automatique n'est configure pour autant -- un rejeu reste une
     * decision de l'appelant, pas un effet de bord du client HTTP.
     */
    public TripSnapshot reserve(Long listingId, java.math.BigDecimal weight, Long transactionId) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doReserve(listingId, weight, transactionId), CircuitFallbacks.failFast(CIRCUIT));
    }

    /** Rend a l'annonce le poids pris par une transaction. Sans effet si rien n'est a rendre. */
    public TripSnapshot release(Long listingId, Long transactionId) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doRelease(listingId, transactionId), CircuitFallbacks.failFast(CIRCUIT));
    }

    public TripSnapshot fetch(Long listingId) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doFetch(listingId), CircuitFallbacks.failFast(CIRCUIT));
    }

    private TripSnapshot doReserve(Long listingId, java.math.BigDecimal weight, Long transactionId) {
        try {
            return restClient.post()
                    .uri("/trips/internal/{id}/reserve", listingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .body(Map.of("weight", weight, "transactionId", transactionId))
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

    private TripSnapshot doRelease(Long listingId, Long transactionId) {
        try {
            return restClient.post()
                    .uri("/trips/internal/{id}/release", listingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .body(Map.of("transactionId", transactionId))
                    .retrieve()
                    .body(TripSnapshot.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new NoSuchElementException("Listing not found: " + listingId);
            }
            throw ex;
        }
    }

    private TripSnapshot doFetch(Long listingId) {
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

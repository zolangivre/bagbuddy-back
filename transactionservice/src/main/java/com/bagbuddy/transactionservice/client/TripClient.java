package com.bagbuddy.transactionservice.client;

import com.bagbuddy.transactionservice.web.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

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
                .run(() -> doReserve(listingId, weight, transactionId), failFast());
    }

    /** Rend a l'annonce le poids pris par une transaction. Sans effet si rien n'est a rendre. */
    public TripSnapshot release(Long listingId, Long transactionId) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doRelease(listingId, transactionId), failFast());
    }

    public TripSnapshot fetch(Long listingId) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doFetch(listingId), failFast());
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

    /**
     * Le repli ne fabrique jamais de valeur de remplacement : on ne tarife pas une
     * reservation contre une annonce qu'on n'a pas lue, et un prix par defaut
     * serait pire qu'une erreur. Les reponses metier (introuvable, refuse,
     * invalide) remontent intactes ; tout le reste devient une indisponibilite
     * explicite, que le coupe-circuit ne confonde pas avec une regle du domaine.
     */
    private static <T> Function<Throwable, T> failFast() {
        return throwable -> {
            if (throwable instanceof NoSuchElementException
                    || throwable instanceof IllegalArgumentException
                    || throwable instanceof AccessDeniedException) {
                throw (RuntimeException) throwable;
            }
            throw new ServiceUnavailableException(CIRCUIT, throwable);
        };
    }
}

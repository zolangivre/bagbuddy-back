package com.bagbuddy.stripeservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Le compte Connect d'un membre vit dans son profil userservice : c'est la seule base qui connait
 * les membres. stripeservice le lit pour verser, et l'ecrit apres avoir cree le compte -- toujours
 * par l'endpoint interne garde par le role 'service', jamais depuis une saisie du membre.
 */
@Component
public class UserClient {

    private static final String CIRCUIT = "userservice";

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public UserClient(RestClient.Builder builder,
                      ServiceTokenProvider tokenProvider,
                      CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                      @Value("${bagbuddy.user-service.url}") String userServiceUrl) {
        this.restClient = builder.clone().baseUrl(userServiceUrl).build();
        this.tokenProvider = tokenProvider;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /** @return l'identifiant acct_... du membre, ou null s'il n'en a pas */
    public String payoutAccount(String sub) {
        return circuitBreakerFactory.create(CIRCUIT).run(() -> {
            PayoutAccount body = restClient.get()
                    .uri("/users/internal/{sub}/payout-account", sub)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .retrieve()
                    .body(PayoutAccount.class);
            return body == null ? null : body.stripeAccountId();
        }, CircuitFallbacks.failFast(CIRCUIT));
    }

    public void savePayoutAccount(String sub, String stripeAccountId) {
        circuitBreakerFactory.create(CIRCUIT).run(() -> {
            restClient.put()
                    .uri("/users/internal/{sub}/payout-account", sub)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .body(new PayoutAccount(stripeAccountId))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }, CircuitFallbacks.failFast(CIRCUIT));
    }

    public record PayoutAccount(String stripeAccountId) {
    }
}

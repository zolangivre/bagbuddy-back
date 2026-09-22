package com.bagbuddy.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Cle de comptage du RequestRateLimiter : l'adresse de l'appelant.
 *
 * L'inscription est anonyme et declenche des appels a l'API d'administration de
 * Keycloak ; il n'y a donc pas d'identite sur laquelle compter, seulement une
 * provenance.
 *
 * X-Forwarded-For est lu en premier parce que derriere un reverse proxy toutes
 * les requetes arrivent avec l'adresse du proxy : compter dessus reviendrait a
 * partager un seul quota entre tous les utilisateurs. L'en-tete etant falsifiable
 * par le client, il ne doit etre pris en compte que si le proxy en amont est de
 * confiance -- ce qui est le cas ici, le gateway n'etant pas expose directement.
 */
@Configuration
public class RateLimitConfig {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> {
            String forwarded = exchange.getRequest().getHeaders().getFirst(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                // Le premier de la liste est le client d'origine.
                return Mono.just(forwarded.split(",")[0].trim());
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just(remote == null || remote.getAddress() == null
                    ? "unknown"
                    : remote.getAddress().getHostAddress());
        };
    }
}

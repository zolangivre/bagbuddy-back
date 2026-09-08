package com.bagbuddy.userservice.config;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class KeycloakConfig {
    @Bean
    @Lazy
    public Keycloak keycloak() {
        return KeycloakBuilder.builder()
                .serverUrl("http://keycloak:8080/")
                .realm("bagbuddy")
                .clientId("bagbuddy")
                .clientSecret("46144xbKPUiHAlyIJrOC4PoLPyRUJpgR")
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }
}
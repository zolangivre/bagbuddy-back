package com.bagbuddy.userservice.client;

import com.fasterxml.jackson.databind.JsonNode;

/** The few fields of a Keycloak user representation this service reads. */
public record KeycloakUser(String id, String email, String firstName, boolean enabled,
                           boolean emailVerified) {

    static KeycloakUser of(JsonNode user) {
        return new KeycloakUser(
                user.path("id").asText(null),
                user.path("email").asText(null),
                user.path("firstName").asText(null),
                user.path("enabled").asBoolean(false),
                user.path("emailVerified").asBoolean(false));
    }
}

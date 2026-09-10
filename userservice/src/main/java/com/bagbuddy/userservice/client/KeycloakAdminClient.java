package com.bagbuddy.userservice.client;

import com.bagbuddy.userservice.web.AccountException;
import com.bagbuddy.userservice.web.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keycloak's admin API, reached with the {@code bagbuddy-accounts} service account.
 *
 * This is the price of serving our own sign-up and account screens instead of Keycloak's:
 * creating a user, changing an email or setting a password are admin operations, and a
 * browser can never hold the credentials that authorise them. They stay here, behind a
 * service that only ever acts on the caller's own {@code sub}.
 */
@Component
public class KeycloakAdminClient {

    private final RestClient keycloak;
    private final ServiceTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final String realm;
    private final String loginClientId;

    public KeycloakAdminClient(RestClient.Builder builder,
                               ServiceTokenProvider tokenProvider,
                               ObjectMapper objectMapper,
                               CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                               @Value("${bagbuddy.keycloak.base-url}") String baseUrl,
                               @Value("${bagbuddy.keycloak.realm}") String realm,
                               @Value("${bagbuddy.keycloak.login-client-id}") String loginClientId) {
        this.keycloak = builder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.realm = realm;
        this.loginClientId = loginClientId;
    }


    private static final String CIRCUIT = "keycloak";

    /** Creates an enabled user with a permanent password. The email doubles as the username. */
    public void createUser(String email, String firstName, String lastName, String password) {
        run(() -> {
            createUserInternal(email, firstName, lastName, password);
            return null;
        });
    }

    public void updateIdentity(String userId, String email, String firstName, String lastName,
                               boolean emailChanged) {
        run(() -> {
            updateIdentityInternal(userId, email, firstName, lastName, emailChanged);
            return null;
        });
    }

    public void resetPassword(String userId, String password) {
        run(() -> {
            resetPasswordInternal(userId, password);
            return null;
        });
    }

    public boolean passwordMatches(String username, String password) {
        return run(() -> passwordMatchesInternal(username, password));
    }

    private <T> T run(Supplier<T> call) {
        return circuitBreakerFactory.create(CIRCUIT).run(call, failFast());
    }

    /**
     * Aucun repli n'invente de reponse : un compte n'est pas cree, ni un mot de passe valide,
     * parce que Keycloak n'a pas repondu. Les issues metier passent telles quelles ; le reste
     * devient une indisponibilite explicite, que le client peut reessayer.
     */
    private static <T> Function<Throwable, T> failFast() {
        return throwable -> {
            if (throwable instanceof AccountException
                    || throwable instanceof NoSuchElementException
                    || throwable instanceof IllegalArgumentException) {
                throw (RuntimeException) throwable;
            }
            throw new ServiceUnavailableException(CIRCUIT, throwable);
        };
    }

    /** Creates an enabled user with a permanent password. The email doubles as the username. */
    private void createUserInternal(String email, String firstName, String lastName, String password) {
        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        try {
            keycloak.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw translate(ex, "email_already_used", "This email is already registered.");
        }
    }

    /**
     * Updates the identity Keycloak owns. A new email resets {@code emailVerified}: nothing
     * has proved the new address belongs to the account holder.
     */
    private void updateIdentityInternal(String userId, String email, String firstName, String lastName,
                               boolean emailChanged) {
        Map<String, Object> body = emailChanged
                ? Map.of("username", email, "email", email, "emailVerified", false,
                         "firstName", firstName, "lastName", lastName)
                : Map.of("firstName", firstName, "lastName", lastName);
        try {
            keycloak.put()
                    .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw translate(ex, "email_already_used", "This email is already in use.");
        }
    }

    private void resetPasswordInternal(String userId, String password) {
        try {
            keycloak.put()
                    .uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw translate(ex, "password_rejected", "This password was rejected.");
        }
    }

    /**
     * Checks a password by asking Keycloak for a token with it. There is no admin endpoint
     * that verifies a password, and we would not want one: this way the realm's own brute
     * force protection sees the attempt.
     */
    private boolean passwordMatchesInternal(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", loginClientId);
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        try {
            keycloak.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * Turns a Keycloak error into something a form can show. 409 means the email is taken;
     * 400 is usually the password policy, and Keycloak's own message is the useful one.
     */
    private RuntimeException translate(RestClientResponseException ex, String conflictCode, String conflictMessage) {
        if (ex.getStatusCode().value() == 409) {
            return new AccountException(HttpStatus.CONFLICT, conflictCode, conflictMessage);
        }
        if (ex.getStatusCode().value() == 400) {
            return new AccountException(HttpStatus.BAD_REQUEST, "password_rejected", errorMessage(ex));
        }
        return ex;
    }

    private String errorMessage(RestClientResponseException ex) {
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            JsonNode message = body.get("errorMessage");
            if (message != null && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Keycloak did not answer with JSON: the generic message below will do.
        }
        return "Keycloak rejected this value.";
    }
}

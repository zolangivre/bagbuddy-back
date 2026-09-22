package com.bagbuddy.userservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/** Obtains (and lets Spring cache) the machine-to-machine access token. */
@Component
public class ServiceTokenProvider {

    private static final Authentication SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
            "bagbuddy-accounts", "bagbuddy-accounts",
            AuthorityUtils.createAuthorityList("ROLE_SERVICE"));

    private final OAuth2AuthorizedClientManager manager;
    private final String registrationId;

    public ServiceTokenProvider(OAuth2AuthorizedClientManager manager,
                                @Value("${bagbuddy.service-client.registration-id}") String registrationId) {
        this.manager = manager;
        this.registrationId = registrationId;
    }

    public String tokenValue() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                .principal(SERVICE_PRINCIPAL)
                .build();
        OAuth2AuthorizedClient client = manager.authorize(request);
        if (client == null) {
            throw new IllegalStateException("Could not obtain a service token for " + registrationId);
        }
        return client.getAccessToken().getTokenValue();
    }
}

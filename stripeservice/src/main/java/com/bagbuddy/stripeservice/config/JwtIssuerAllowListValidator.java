package com.bagbuddy.stripeservice.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Accepts a token only if its issuer is one of the configured realm URLs.
 *
 * A list rather than a single value because the same Keycloak realm is reachable under two
 * names: the browser obtains its token from http://localhost:8000 while a service obtains
 * its client-credentials token from http://keycloak:8080. Both are the same realm signing
 * with the same keys -- and the signature, checked against the pinned JWKS endpoint, is what
 * actually establishes trust here.
 */
public class JwtIssuerAllowListValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_ISSUER = new OAuth2Error(
            "invalid_token", "The issuer is not trusted", null);

    private final List<String> allowedIssuers;

    public JwtIssuerAllowListValidator(List<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
        if (issuer != null && allowedIssuers.contains(issuer)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_ISSUER);
    }
}

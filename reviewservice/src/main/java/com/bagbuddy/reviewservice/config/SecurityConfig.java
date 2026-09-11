package com.bagbuddy.reviewservice.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Every endpoint requires a valid Keycloak access token. The JWKS endpoint is configured
 * separately from the issuers on purpose: inside Docker the services reach Keycloak on
 * http://keycloak:8080 while the token the browser obtained carries the public issuer
 * (http://localhost:8000/realms/bagbuddy). Using jwk-set-uri also keeps decoder creation
 * lazy, so a service still boots when Keycloak is not up yet.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${bagbuddy.auth.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${bagbuddy.auth.issuer-uris}")
    private List<String> issuerUris;

    @Value("${bagbuddy.auth.audience}")
    private String audience;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer-token API: no cookies, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Sans ca, une erreur de validation (400) repart en 401 : le
                        // forward vers /error repasse par la chaine de securite, qui ne
                        // voit plus de jeton sur la requete interne.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Console GraphiQL : page statique, sans donnees. Les requetes
                        // qu'elle emet passent par /reviews/graphql et restent authentifiees.
                        .requestMatchers("/reviews/graphiql/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerAllowListValidator(issuerUris),
                new JwtAudienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}

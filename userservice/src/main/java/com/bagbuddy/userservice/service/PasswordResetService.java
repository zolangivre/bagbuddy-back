package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.client.KeycloakUser;
import com.bagbuddy.userservice.dto.RequestPasswordResetRequest;
import com.bagbuddy.userservice.dto.ResetPasswordRequest;
import com.bagbuddy.userservice.model.AccountToken;
import com.bagbuddy.userservice.web.AccountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

/**
 * Forgotten password, served by our own screens: the link lands on the web front, never on a
 * Keycloak page. Keycloak can send such an email itself (execute-actions-email), but its link
 * opens its own theme, which the web front deliberately never shows.
 *
 * Both operations are anonymous — the person asking has, by definition, no token.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final KeycloakAdminClient keycloak;
    private final AccountTokenStore tokens;
    private final AccountMailer mailer;
    private final String frontUrl;
    private final Duration validity;
    private final Duration minInterval;

    public PasswordResetService(KeycloakAdminClient keycloak, AccountTokenStore tokens,
                                AccountMailer mailer,
                                @Value("${bagbuddy.front-url}") String frontUrl,
                                @Value("${bagbuddy.password-reset.validity}") Duration validity,
                                @Value("${bagbuddy.password-reset.min-interval}") Duration minInterval) {
        this.keycloak = keycloak;
        this.tokens = tokens;
        this.mailer = mailer;
        this.frontUrl = frontUrl.replaceAll("/+$", "");
        this.validity = validity;
        this.minInterval = minInterval;
    }

    /**
     * Runs off the request thread, and the resolver answers {@code true} straight away. The
     * answer must not tell whether the email belongs to an account — neither by its content
     * nor by how long it takes, and looking the account up then sending an email takes
     * noticeably longer than finding nothing.
     *
     * The token travels in the URL fragment: a fragment is never sent to a server, so the link
     * does not end up in the access logs of the front's host.
     */
    @Async
    public void requestReset(RequestPasswordResetRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        try {
            KeycloakUser user = keycloak.findUserByEmail(email)
                    .filter(KeycloakUser::enabled)
                    .orElse(null);
            if (user == null) {
                return;
            }
            tokens.issue(user.id(), email, AccountToken.Purpose.PASSWORD_RESET, validity, minInterval)
                    .ifPresent(token -> mailer.sendPasswordReset(email, user.firstName(),
                            frontUrl + "/reset-password#" + token, validity, request.getLanguage()));
        } catch (RuntimeException ex) {
            // Nobody is waiting for this answer: the failure can only be logged.
            log.error("Password reset email could not be sent", ex);
        }
    }

    /**
     * Sets the new password, then ends every open session: whoever knew the old password must
     * not stay signed in on another device.
     *
     * The token stays locked for the whole operation and is burned only once Keycloak accepted
     * the password, so a password refused by the realm policy does not cost the link.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        AccountToken token = tokens.findUsable(request.getToken(), AccountToken.Purpose.PASSWORD_RESET)
                .orElseThrow(PasswordResetService::invalidToken);

        KeycloakUser user = keycloak.findUser(token.getSub())
                .filter(KeycloakUser::enabled)
                .filter(found -> token.getEmail().equalsIgnoreCase(found.email()))
                .orElseThrow(PasswordResetService::invalidToken);

        keycloak.resetPassword(user.id(), request.getNewPassword());
        tokens.redeem(token);

        try {
            keycloak.logout(user.id());
        } catch (RuntimeException ex) {
            // The password is already changed: failing now would tell the user it was not.
            log.warn("Sessions of {} could not be ended after a password reset", user.id(), ex);
        }
    }

    private static AccountException invalidToken() {
        return new AccountException(HttpStatus.BAD_REQUEST, "invalid_reset_token",
                "This reset link is invalid or has expired.");
    }
}

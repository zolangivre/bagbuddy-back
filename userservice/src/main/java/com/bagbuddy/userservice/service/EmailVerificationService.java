package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.client.KeycloakUser;
import com.bagbuddy.userservice.model.AccountToken;
import com.bagbuddy.userservice.repository.UserRepository;
import com.bagbuddy.userservice.web.AccountException;
import com.bagbuddy.userservice.web.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.NoSuchElementException;

/**
 * Email verification, served by our own screens like the forgotten password: the link lands on
 * the web front ({@code /verify-email#<token>}), never on Keycloak's page.
 *
 * Sending is for the signed-in account holder, to their current address. Verifying is
 * anonymous, because the link is often opened on another device (a phone's mail app) than the
 * one that asked for it; the token alone designates the account.
 */
@Service
public class EmailVerificationService {

    private final KeycloakAdminClient keycloak;
    private final AccountTokenStore tokens;
    private final AccountMailer mailer;
    private final UserRepository userRepository;
    private final String frontUrl;
    private final Duration validity;
    private final Duration minInterval;

    public EmailVerificationService(KeycloakAdminClient keycloak, AccountTokenStore tokens,
                                    AccountMailer mailer, UserRepository userRepository,
                                    @Value("${bagbuddy.front-url}") String frontUrl,
                                    @Value("${bagbuddy.email-verification.validity}") Duration validity,
                                    @Value("${bagbuddy.email-verification.min-interval}") Duration minInterval) {
        this.keycloak = keycloak;
        this.tokens = tokens;
        this.mailer = mailer;
        this.userRepository = userRepository;
        this.frontUrl = frontUrl.replaceAll("/+$", "");
        this.validity = validity;
        this.minInterval = minInterval;
    }

    /**
     * Mails a link to the caller's current address, read from Keycloak rather than from the
     * token: right after an email change, the token still carries the old one.
     *
     * Returns false when the address is already verified. One transaction around the token
     * and the email: if the email cannot be sent, the token is rolled back, and the minimum
     * interval does not stop the caller from trying again straight away.
     */
    @Transactional
    public boolean send(Jwt caller, String language) {
        KeycloakUser user = keycloak.findUser(caller.getSubject())
                .orElseThrow(() -> new NoSuchElementException("No account for " + caller.getSubject()));
        if (user.emailVerified()) {
            return false;
        }
        String token = tokens.issue(user.id(), user.email(), AccountToken.Purpose.EMAIL_VERIFICATION,
                        validity, minInterval)
                .orElseThrow(() -> new AccountException(HttpStatus.TOO_MANY_REQUESTS,
                        "verification_email_throttled",
                        "A verification email was sent less than a minute ago."));
        try {
            mailer.sendEmailVerification(user.email(), user.firstName(),
                    frontUrl + "/verify-email#" + token, validity, language);
        } catch (MailException ex) {
            throw new ServiceUnavailableException("mail", ex);
        }
        return true;
    }

    /**
     * Marks the address verified in Keycloak, and in the local profile when there is one, so
     * that other members see the badge without waiting for the holder's next {@code me}.
     *
     * Refused when the account has since moved to another address: the link proved ownership
     * of the old one only.
     */
    @Transactional
    public void verify(String rawToken) {
        AccountToken token = tokens.findUsable(rawToken, AccountToken.Purpose.EMAIL_VERIFICATION)
                .orElseThrow(EmailVerificationService::invalidToken);

        KeycloakUser user = keycloak.findUser(token.getSub())
                .filter(KeycloakUser::enabled)
                .filter(found -> token.getEmail().equalsIgnoreCase(found.email()))
                .orElseThrow(EmailVerificationService::invalidToken);

        if (!user.emailVerified()) {
            keycloak.markEmailVerified(user.id());
        }
        tokens.redeem(token);
        userRepository.findBySub(user.id()).ifPresent(profile -> {
            profile.setEmailVerified(true);
            userRepository.save(profile);
        });
    }

    private static AccountException invalidToken() {
        return new AccountException(HttpStatus.BAD_REQUEST, "invalid_verification_token",
                "This verification link is invalid or has expired.");
    }
}

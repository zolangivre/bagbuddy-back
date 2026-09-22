package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.dto.ChangePasswordRequest;
import com.bagbuddy.userservice.dto.RegisterRequest;
import com.bagbuddy.userservice.dto.UpdateIdentityRequest;
import com.bagbuddy.userservice.model.User;
import com.bagbuddy.userservice.repository.UserRepository;
import com.bagbuddy.userservice.web.AccountException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Account lifecycle: sign-up, identity, password. Keycloak remains the source of truth —
 * this service only carries out, on behalf of the caller, what the browser cannot do itself.
 *
 * Every operation acts on the caller's own {@code sub}: there is no user id in any signature
 * on purpose, so a bug here cannot turn into editing somebody else's account.
 */
@Service
public class AccountService {

    private final KeycloakAdminClient keycloak;
    private final UserService userService;
    private final UserRepository userRepository;

    public AccountService(KeycloakAdminClient keycloak, UserService userService,
                          UserRepository userRepository) {
        this.keycloak = keycloak;
        this.userService = userService;
        this.userRepository = userRepository;
    }

    /**
     * Creates the Keycloak account. The application profile is not created here: it appears
     * on the first GET /users/me, from the claims of the token the front just obtained.
     */
    public void register(RegisterRequest request) {
        keycloak.createUser(normalize(request.getEmail()), request.getFirstName().trim(),
                request.getLastName().trim(), request.getPassword());
    }

    /**
     * Changes name and email in Keycloak, then mirrors them locally so the profile is right
     * before the front has refreshed its token.
     *
     * The email is also the login name, so changing it takes the current password, like
     * changing the password does: otherwise a session left open would be enough to move the
     * account onto an address its holder can no longer sign in with.
     */
    @Transactional
    public User updateIdentity(Jwt caller, UpdateIdentityRequest request) {
        User user = userService.currentProfile(caller);
        String email = normalize(request.getEmail());
        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();
        boolean emailChanged = !email.equalsIgnoreCase(user.getEmail());
        if (emailChanged) {
            requireCurrentPassword(caller, request.getCurrentPassword());
        }

        keycloak.updateIdentity(caller.getSubject(), email, firstName, lastName, emailChanged);

        user.setGivenName(firstName);
        user.setFamilyName(lastName);
        user.setName((firstName + " " + lastName).trim());
        if (emailChanged) {
            user.setEmail(email);
            user.setUsername(email);
            user.setEmailVerified(false);
        }
        return userRepository.save(user);
    }

    /**
     * A valid session is not enough to set a new password: an unattended browser would be.
     * The current password is checked against Keycloak first.
     */
    public void changePassword(Jwt caller, ChangePasswordRequest request) {
        requireCurrentPassword(caller, request.getCurrentPassword());
        keycloak.resetPassword(caller.getSubject(), request.getNewPassword());
    }

    /**
     * Checked against the caller's sub, never against a login name taken from the token: that
     * claim can be stale, and the name it holds may since have been registered by someone else.
     */
    private void requireCurrentPassword(Jwt caller, String currentPassword) {
        if (currentPassword == null || currentPassword.isBlank()
                || !keycloak.passwordMatches(caller.getSubject(), currentPassword)) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "invalid_current_password",
                    "The current password is incorrect.");
        }
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

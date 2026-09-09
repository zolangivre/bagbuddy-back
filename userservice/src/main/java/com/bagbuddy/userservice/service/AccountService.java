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
     */
    @Transactional
    public User updateIdentity(Jwt caller, UpdateIdentityRequest request) {
        User user = userService.currentProfile(caller);
        String email = normalize(request.getEmail());
        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();
        boolean emailChanged = !email.equalsIgnoreCase(user.getEmail());

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
        String username = caller.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            username = caller.getClaimAsString("email");
        }
        if (!keycloak.passwordMatches(username, request.getCurrentPassword())) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "invalid_current_password",
                    "The current password is incorrect.");
        }
        keycloak.resetPassword(caller.getSubject(), request.getNewPassword());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.dto.UpdateProfileRequest;
import com.bagbuddy.userservice.model.User;
import com.bagbuddy.userservice.repository.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class UserService {

    private static final Pattern STRIPE_ACCOUNT = Pattern.compile("^acct_[A-Za-z0-9]{1,64}$");

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the caller's profile, creating it on first sight. Identity fields are refreshed
     * from the token on every call, so Keycloak stays the source of truth for them and a
     * client can never rewrite its own email or username through this service.
     */
    @Transactional
    public User currentProfile(Jwt caller) {
        User user = userRepository.findBySub(caller.getSubject()).orElseGet(User::new);
        user.setSub(caller.getSubject());
        user.setEmail(caller.getClaimAsString("email"));
        user.setEmailVerified(Boolean.TRUE.equals(caller.getClaimAsBoolean("email_verified")));
        user.setUsername(caller.getClaimAsString("preferred_username"));
        user.setName(caller.getClaimAsString("name"));
        user.setGivenName(caller.getClaimAsString("given_name"));
        user.setFamilyName(caller.getClaimAsString("family_name"));
        return userRepository.save(user);
    }

    @Transactional
    public User updateCurrentProfile(Jwt caller, UpdateProfileRequest request) {
        User user = currentProfile(caller);
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getLocation() != null) {
            user.setLocation(request.getLocation());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        return userRepository.save(user);
    }

    /** Compte Stripe Connect du membre, s'il en a un. Lu par stripeservice pour verser. */
    public Optional<String> payoutAccount(String sub) {
        return userRepository.findBySub(sub).map(User::getStripeAccountId);
    }

    /**
     * Enregistre le compte Connect cree par stripeservice. Le profil est cree s'il n'existe pas
     * encore : le membre est forcement connecte pour lancer l'onboarding, mais rien ne garantit
     * que le front ait deja appele me.
     */
    @Transactional
    public void savePayoutAccount(String sub, String stripeAccountId) {
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("sub is required");
        }
        if (stripeAccountId == null || !STRIPE_ACCOUNT.matcher(stripeAccountId).matches()) {
            throw new IllegalArgumentException("stripeAccountId must look like acct_...");
        }
        User user = userRepository.findBySub(sub).orElseGet(User::new);
        user.setSub(sub);
        user.setStripeAccountId(stripeAccountId);
        userRepository.save(user);
    }

    public User publicProfile(String sub) {
        return userRepository.findBySub(sub)
                .orElseThrow(() -> new NoSuchElementException("No profile for user " + sub));
    }
}

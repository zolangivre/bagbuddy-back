package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.dto.ChangePasswordRequest;
import com.bagbuddy.userservice.dto.PublicUserProfile;
import com.bagbuddy.userservice.dto.RegisterRequest;
import com.bagbuddy.userservice.dto.RequestPasswordResetRequest;
import com.bagbuddy.userservice.dto.ResetPasswordRequest;
import com.bagbuddy.userservice.dto.UpdateIdentityRequest;
import com.bagbuddy.userservice.dto.UpdateProfileRequest;
import com.bagbuddy.userservice.dto.UserProfile;
import com.bagbuddy.userservice.service.AccountService;
import com.bagbuddy.userservice.service.PasswordResetService;
import com.bagbuddy.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

/**
 * Profils applicatifs et cycle de vie du compte, reunis derriere un unique /users/graphql.
 *
 * ATTENTION -- regle de securite propre a ce service. Comme l'inscription doit rester joignable
 * sans jeton et que GraphQL n'expose qu'une seule URL, /users/graphql est ouvert dans
 * SecurityConfig. L'authentification est donc portee ici, methode par methode :
 * <strong>toute operation ajoutee a ce controleur doit recevoir @PreAuthorize, sans quoi elle
 * devient accessible anonymement</strong>. UserProfileSecurityTest verifie cette regle en
 * appelant chaque operation sans jeton.
 *
 * Toutes les operations agissent sur le sub du jeton : aucune signature ne prend d'identifiant
 * d'utilisateur, pour qu'un bug ici ne puisse pas devenir la modification du compte d'autrui.
 */
@Controller
@Validated
public class UserGraphQlController {

    private final UserService userService;
    private final AccountService accountService;
    private final PasswordResetService passwordResetService;

    public UserGraphQlController(UserService userService, AccountService accountService,
                                 PasswordResetService passwordResetService) {
        this.userService = userService;
        this.accountService = accountService;
        this.passwordResetService = passwordResetService;
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return UserProfile.of(userService.currentProfile(jwt));
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public PublicUserProfile user(@Argument String sub, @AuthenticationPrincipal Jwt jwt) {
        if (sub.equals(jwt.getSubject())) {
            return PublicUserProfile.of(userService.currentProfile(jwt), true);
        }
        return PublicUserProfile.of(userService.publicProfile(sub), false);
    }

    /** Operation anonyme, comme les deux de reinitialisation. Voir la note de securite de la classe. */
    @MutationMapping
    public boolean register(@Argument @Valid RegisterRequest input) {
        accountService.register(input);
        return true;
    }

    /**
     * Anonyme par nature : qui a oublie son mot de passe n'a pas de jeton. L'annotation est
     * explicite pour qu'une operation sans @PreAuthorize reste, a la lecture, un oubli.
     *
     * Repond toujours true, compte ou pas, et avant meme de l'avoir cherche : ni le contenu ni
     * le temps de reponse ne doivent dire qui est inscrit.
     */
    @MutationMapping
    @PreAuthorize("permitAll()")
    public boolean requestPasswordReset(@Argument @Valid RequestPasswordResetRequest input) {
        passwordResetService.requestReset(input);
        return true;
    }

    /** Anonyme : c'est le jeton du lien envoye par email qui designe le compte. */
    @MutationMapping
    @PreAuthorize("permitAll()")
    public boolean resetPassword(@Argument @Valid ResetPasswordRequest input) {
        passwordResetService.resetPassword(input);
        return true;
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public UserProfile updateProfile(@Argument @Valid UpdateProfileRequest input,
                                     @AuthenticationPrincipal Jwt jwt) {
        return UserProfile.of(userService.updateCurrentProfile(jwt, input));
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public UserProfile updateIdentity(@Argument @Valid UpdateIdentityRequest input,
                                      @AuthenticationPrincipal Jwt jwt) {
        return UserProfile.of(accountService.updateIdentity(jwt, input));
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean changePassword(@Argument @Valid ChangePasswordRequest input,
                                  @AuthenticationPrincipal Jwt jwt) {
        accountService.changePassword(jwt, input);
        return true;
    }
}

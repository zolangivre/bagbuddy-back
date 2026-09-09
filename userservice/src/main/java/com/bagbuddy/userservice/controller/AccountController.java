package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.dto.ChangePasswordRequest;
import com.bagbuddy.userservice.dto.RegisterRequest;
import com.bagbuddy.userservice.dto.UpdateIdentityRequest;
import com.bagbuddy.userservice.dto.UserProfile;
import com.bagbuddy.userservice.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account lifecycle, so the front can host its own sign-up and account screens instead of
 * redirecting to Keycloak's pages.
 *
 * Only /users/register is public. The other two act on the caller's token subject, never on
 * a user id taken from the request.
 */
@RestController
@RequestMapping("/users")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        accountService.register(request);
    }

    @PutMapping("/me/identity")
    public UserProfile updateIdentity(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody UpdateIdentityRequest request) {
        return UserProfile.of(accountService.updateIdentity(jwt, request));
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(jwt, request);
    }
}

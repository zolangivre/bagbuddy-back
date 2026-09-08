package com.bagbuddy.userservice.controller;

import com.bagbuddy.userservice.dto.PublicUserProfile;
import com.bagbuddy.userservice.dto.UpdateProfileRequest;
import com.bagbuddy.userservice.dto.UserProfile;
import com.bagbuddy.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Profiles only. There is deliberately no endpoint to list every user, create an account or
 * set a password: account lifecycle belongs to Keycloak.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return UserProfile.of(userService.currentProfile(jwt));
    }

    @PutMapping("/me")
    public UserProfile updateMe(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody UpdateProfileRequest request) {
        return UserProfile.of(userService.updateCurrentProfile(jwt, request));
    }

    /** Public-facing profile of another member: no email, no phone, no payout account. */
    @GetMapping("/{sub}")
    public PublicUserProfile publicProfile(@PathVariable String sub, @AuthenticationPrincipal Jwt jwt) {
        if (sub.equals(jwt.getSubject())) {
            return PublicUserProfile.of(userService.currentProfile(jwt));
        }
        return PublicUserProfile.of(userService.publicProfile(sub));
    }
}

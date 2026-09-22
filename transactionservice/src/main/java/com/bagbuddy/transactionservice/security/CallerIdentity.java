package com.bagbuddy.transactionservice.security;

import com.bagbuddy.transactionservice.model.UserInfo;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identity always comes from the verified token, never from the request body.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    public static String subOf(Jwt jwt) {
        return jwt.getSubject();
    }

    public static UserInfo fromToken(Jwt jwt, UserInfo submitted) {
        UserInfo info = new UserInfo();
        info.setSub(jwt.getSubject());
        info.setEmail(jwt.getClaimAsString("email"));
        info.setEmail_verified(Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
        info.setName(jwt.getClaimAsString("name"));
        info.setGiven_name(jwt.getClaimAsString("given_name"));
        info.setFamily_name(jwt.getClaimAsString("family_name"));
        info.setUsername(jwt.getClaimAsString("preferred_username"));
        if (submitted != null) {
            info.setBio(submitted.getBio());
            info.setLocation(submitted.getLocation());
            info.setPhone(submitted.getPhone());
        }
        return info;
    }
}

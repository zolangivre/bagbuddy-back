package com.bagbuddy.tripservice.security;

import com.bagbuddy.tripservice.model.UserInfo;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Single source of truth for "who is calling". Identity fields are always read from the
 * verified access token, never from the request body: a client that sends its own
 * userInfo.sub must not be able to publish or edit a trip on behalf of somebody else.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    public static String subOf(Jwt jwt) {
        return jwt.getSubject();
    }

    /**
     * Builds the denormalised snapshot stored on the trip. Identity claims come from the
     * token; the free-text profile fields (bio, location, phone) are the caller's own data
     * and may be supplied by the client.
     */
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

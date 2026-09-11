package com.bagbuddy.userservice.dto;

import com.bagbuddy.userservice.model.User;
import lombok.Data;

/**
 * What any authenticated user may see about somebody else. Deliberately excludes email,
 * phone and the Stripe account: those are contact/payout details, not public profile.
 *
 * The username is withheld too, except from the account holder: sign-up uses the email as
 * the Keycloak username, so handing it out would hand out the email under another name.
 */
@Data
public class PublicUserProfile {

    private String sub;
    private String username;
    private String name;
    private String givenName;
    private boolean emailVerified;
    private String bio;
    private String location;

    public static PublicUserProfile of(User user, boolean self) {
        PublicUserProfile dto = new PublicUserProfile();
        dto.sub = user.getSub();
        dto.username = self ? user.getUsername() : null;
        dto.name = user.getName();
        dto.givenName = user.getGivenName();
        dto.emailVerified = user.isEmailVerified();
        dto.bio = user.getBio();
        dto.location = user.getLocation();
        return dto;
    }
}

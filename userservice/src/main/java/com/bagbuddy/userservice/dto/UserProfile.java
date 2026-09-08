package com.bagbuddy.userservice.dto;

import com.bagbuddy.userservice.model.User;
import lombok.Data;

/** Full profile: only ever returned to the owner. */
@Data
public class UserProfile {

    private String sub;
    private String email;
    private boolean emailVerified;
    private String username;
    private String name;
    private String givenName;
    private String familyName;
    private String bio;
    private String location;
    private String phone;
    private String stripeAccountId;

    public static UserProfile of(User user) {
        UserProfile dto = new UserProfile();
        dto.sub = user.getSub();
        dto.email = user.getEmail();
        dto.emailVerified = user.isEmailVerified();
        dto.username = user.getUsername();
        dto.name = user.getName();
        dto.givenName = user.getGivenName();
        dto.familyName = user.getFamilyName();
        dto.bio = user.getBio();
        dto.location = user.getLocation();
        dto.phone = user.getPhone();
        dto.stripeAccountId = user.getStripeAccountId();
        return dto;
    }
}

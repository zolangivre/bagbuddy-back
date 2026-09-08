package com.bagbuddy.userservice.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The only fields a user may change here. Identity fields are absent on purpose: they come
 * from the token, and changing an email or a username is Keycloak's job, not ours.
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 2000)
    private String bio;

    @Size(max = 120)
    private String location;

    @Size(max = 32)
    @Pattern(regexp = "^$|^[+0-9 ().-]{6,32}$", message = "phone has an unexpected format")
    private String phone;

    @Size(max = 255)
    private String stripeAccountId;
}

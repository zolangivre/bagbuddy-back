package com.bagbuddy.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Sign-up form. The email doubles as the username, so there is no username field. */
@Data
public class RegisterRequest {

    @NotBlank
    @Size(max = 60)
    private String firstName;

    @NotBlank
    @Size(max = 60)
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    /** Mirrors the realm's password policy (length(8)); Keycloak stays the authority. */
    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}

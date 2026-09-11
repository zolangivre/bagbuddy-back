package com.bagbuddy.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The identity fields Keycloak owns, edited from our own account screen. */
@Data
public class UpdateIdentityRequest {

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

    /** Required only when the email changes, since the email is also the login name. */
    @Size(max = 128)
    private String currentPassword;
}

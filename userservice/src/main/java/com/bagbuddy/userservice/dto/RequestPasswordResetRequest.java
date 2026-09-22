package com.bagbuddy.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RequestPasswordResetRequest {

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    /** Language of the email: the one the visitor was using on the front. English otherwise. */
    @Pattern(regexp = "en|fr")
    private String language;
}

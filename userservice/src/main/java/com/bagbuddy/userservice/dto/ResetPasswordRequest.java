package com.bagbuddy.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

    /** The token from the emailed link (43 characters of base64url). */
    @NotBlank
    @Size(max = 64)
    private String token;

    /** Same bounds as sign-up, mirroring the realm's password policy. */
    @NotBlank
    @Size(min = 8, max = 128)
    private String newPassword;
}

package com.bagbuddy.userservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Application-side profile. Identity (credentials, email verification, sessions) stays in
 * Keycloak; this table only owns what the marketplace needs and Keycloak does not carry:
 * bio, location, phone, payout account.
 */
@Data
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Keycloak subject. The join key used by every other service. */
    @Column(unique = true, nullable = false, updatable = false)
    private String sub;

    // --- Mirrored from the access token on every /users/me call ---
    @Column(unique = true)
    private String email;

    private boolean emailVerified;
    private String familyName;
    private String givenName;
    private String name;
    private String username;

    // --- Owned by this service, edited by the user ---
    @Column(columnDefinition = "TEXT")
    private String bio;

    private String location;
    private String phone;
    private String stripeAccountId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

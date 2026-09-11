package com.bagbuddy.userservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Single-use token mailed to an account holder. Only the SHA-256 of the token is stored: the
 * table alone never yields a working link.
 *
 * {@code email} is the address the link was sent to. A link is refused once the account has
 * moved to another address, since whoever reads the old mailbox is no longer the holder.
 */
@Data
@Entity
@Table(name = "account_token")
public class AccountToken {

    public enum Purpose {
        PASSWORD_RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Keycloak subject of the account the token acts on. */
    @Column(nullable = false)
    private String sub;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Purpose purpose;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

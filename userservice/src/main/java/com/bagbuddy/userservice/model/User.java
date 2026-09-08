package com.bagbuddy.userservice.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sub; // ID Keycloak (subject)

    @Column(unique = true)
    private String email;

    private boolean emailVerified;
    private String familyName;
    private String givenName;
    private String name;
    private String username;
    private String bio;
    private String location;
    private String phone;
}
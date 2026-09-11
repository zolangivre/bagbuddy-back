package com.bagbuddy.userservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** A listing a member put aside. The listing itself lives in tripservice. */
@Data
@Entity
@Table(name = "favorite_listing")
public class FavoriteListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sub;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

package com.bagbuddy.transactionservice.model;

import jakarta.persistence.*;
import lombok.Data;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "transaction_record")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private ListingInfo listingInfo;

    private Long listingId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name="email_verified", column=@Column(name="buyer_email_verified")),
            @AttributeOverride(name="email", column=@Column(name="buyer_email")),
            @AttributeOverride(name="bio", column=@Column(name="buyer_bio")),
            @AttributeOverride(name="family_name", column=@Column(name="buyer_family_name")),
            @AttributeOverride(name="given_name", column=@Column(name="buyer_given_name")),
            @AttributeOverride(name="name", column=@Column(name="buyer_name")),
            @AttributeOverride(name="username", column=@Column(name="buyer_username")),
            @AttributeOverride(name="sub", column=@Column(name="buyer_sub")),
            @AttributeOverride(name="location", column=@Column(name="buyer_location")),
            @AttributeOverride(name="phone", column=@Column(name="buyer_phone"))
    })
    private UserInfo buyerInfo;

    private String buyerId;
    private String sellerId;

    private String sellerStatus;
    private String buyerStatus;

    private BigDecimal weight;
    private BigDecimal total;

    private Boolean sellerReview = false;
    private Boolean buyerReview = false;

    @Column(name = "stripe_payment_intent_id", length = 255)
    private String stripePaymentIntentId;

    @Column(name = "stripe_currency", length = 3)
    private String stripeCurrency; // "USD" ou "EUR"

    @Column(name = "stripe_amount")
    private Long stripeAmount;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(nullable = false, updatable = false, name = "transaction_created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
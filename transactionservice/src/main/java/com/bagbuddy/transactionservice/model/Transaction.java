package com.bagbuddy.transactionservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    /** Ce que l'acheteur confie au voyageur, declare a la reservation. */
    @Column(name = "content_description", length = 500)
    private String contentDescription;

    /** L'acheteur a accepte la liste des objets interdits en reservant. */
    @Column(name = "prohibited_items_accepted", nullable = false)
    private boolean prohibitedItemsAccepted;

    /** Genere au paiement, montre a l'acheteur seul, saisi par le voyageur a la livraison. */
    @Column(name = "handover_code", length = 6)
    private String handoverCode;

    @Column(name = "handover_attempts", nullable = false)
    private int handoverAttempts;

    /** Au-dela, le code est bloque : l'acheteur clot lui-meme, ou la moderation tranche. */
    public static final int MAX_HANDOVER_ATTEMPTS = 5;

    /** Trop de codes faux : le voyageur ne peut plus clore par ce chemin. */
    public boolean isHandoverLocked() {
        return handoverAttempts >= MAX_HANDOVER_ATTEMPTS;
    }

    // --- Reglement (SettlementService), en unites mineures comme stripeAmount ---
    @Column(name = "platform_fee")
    private Long platformFee;

    @Column(name = "refund_amount")
    private Long refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", length = 32)
    private SettlementStatus refundStatus;

    @Column(name = "stripe_refund_id")
    private String stripeRefundId;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "payout_amount")
    private Long payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", length = 32)
    private SettlementStatus payoutStatus;

    @Column(name = "stripe_transfer_id")
    private String stripeTransferId;

    @Column(name = "paid_out_at")
    private LocalDateTime paidOutAt;

    @Column(nullable = false, updatable = false, name = "transaction_created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
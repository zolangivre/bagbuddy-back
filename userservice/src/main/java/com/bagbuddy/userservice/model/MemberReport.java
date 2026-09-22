package com.bagbuddy.userservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** A member reported by another one, for the moderators. Never deleted: an audit trail. */
@Data
@Entity
@Table(name = "member_report")
public class MemberReport {

    public enum Reason { PROHIBITED_ITEMS, NO_SHOW, FRAUD, HARASSMENT, OTHER }

    public enum Status { OPEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String reporterSub;

    @Column(nullable = false)
    private String reportedSub;

    /** The transaction the report is about, when it came from one. */
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Reason reason;

    @Column(length = 2000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = Status.OPEN;
        }
    }
}

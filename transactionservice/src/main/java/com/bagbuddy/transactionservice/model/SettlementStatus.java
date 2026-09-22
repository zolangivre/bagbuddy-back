package com.bagbuddy.transactionservice.model;

/** Ou en est un remboursement ou un versement (voir SettlementService). */
public enum SettlementStatus {
    /** Du, pas encore execute : SettlementJob le relance. */
    PENDING,
    /** Versement du, mais le voyageur n'a pas de compte Connect pret : relance aussi. */
    AWAITING_ACCOUNT,
    /** Execute chez Stripe. */
    DONE,
    /** Paiement simule (PAYMENTS_REQUIRE_STRIPE=false) : reparti, sans argent reel. */
    SIMULATED,
    /** Refuse par Stripe de facon definitive : a traiter a la main, plus relance. */
    FAILED
}

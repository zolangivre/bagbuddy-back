package com.bagbuddy.transactionservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversion euros -> centimes, en un seul endroit. C'est la regle qui decide si un reglement
 * s'equilibre contre stripeAmount (SettlementPolicy) et si un paiement correspond au total
 * (markPaid) : deux arrondis differents se traduiraient par des centimes perdus.
 *
 * Same conversion stripeservice uses to build the PaymentIntent amount.
 */
final class Money {

    private Money() {
    }

    static long minorUnits(BigDecimal total) {
        if (total == null) {
            throw new IllegalArgumentException("Transaction has no total");
        }
        return total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
}

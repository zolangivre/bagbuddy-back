package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Qui recoit quoi quand une transaction payee se termine. Tous les montants sont en unites
 * mineures (centimes), comme le paiement Stripe dont ils sont une repartition : remboursement +
 * commission + versement = montant encaisse, au centime pres.
 *
 * Les regles, toutes configurables :
 * <ul>
 *   <li><b>Terminee</b> : le voyageur recoit le montant encaisse moins la commission de la
 *   plateforme ({@code bagbuddy.payments.platform-fee-percent}, 10 %).</li>
 *   <li><b>Annulee par le voyageur</b> : l'acheteur est rembourse en entier. C'est le voyageur qui
 *   se desiste ; il ne touche rien, la plateforme non plus.</li>
 *   <li><b>Annulee par l'acheteur tot</b> (plus de {@code late-cancellation-window}, 24 h, avant le
 *   depart) : rembourse en entier.</li>
 *   <li><b>Annulee par l'acheteur tard</b> : rembourse a {@code late-cancellation-refund-percent}
 *   (50 %). Le reste dedommage le voyageur qui a garde la place, commission deduite de cette part
 *   seulement.</li>
 * </ul>
 */
@Component
public class SettlementPolicy {

    public record Plan(long refund, long platformFee, long payout) {
        public Plan {
            if (refund < 0 || platformFee < 0 || payout < 0) {
                throw new IllegalStateException("A settlement never has a negative part");
            }
        }
    }

    private final int platformFeePercent;
    private final Duration lateCancellationWindow;
    private final int lateCancellationRefundPercent;

    public SettlementPolicy(@Value("${bagbuddy.payments.platform-fee-percent:10}") int platformFeePercent,
                            @Value("${bagbuddy.payments.late-cancellation-window:PT24H}") Duration lateCancellationWindow,
                            @Value("${bagbuddy.payments.late-cancellation-refund-percent:50}") int lateCancellationRefundPercent) {
        if (platformFeePercent < 0 || platformFeePercent > 100) {
            throw new IllegalArgumentException("platform-fee-percent must be between 0 and 100");
        }
        if (lateCancellationRefundPercent < 0 || lateCancellationRefundPercent > 100) {
            throw new IllegalArgumentException("late-cancellation-refund-percent must be between 0 and 100");
        }
        this.platformFeePercent = platformFeePercent;
        this.lateCancellationWindow = lateCancellationWindow;
        this.lateCancellationRefundPercent = lateCancellationRefundPercent;
    }

    public Plan onCompletion(long charged) {
        long fee = percentOf(charged, platformFeePercent);
        return new Plan(0, fee, charged - fee);
    }

    /**
     * @param cancelledBy qui annule ; seul un acheteur qui annule tard perd une partie du paiement,
     *                    toute autre annulation (voyageur, systeme) le rembourse en entier
     * @param departure   depart du vol, null si inconnu (rembourse alors en entier)
     */
    public Plan onCancellation(long charged, Actor cancelledBy, LocalDateTime departure, LocalDateTime now) {
        boolean late = cancelledBy == Actor.BUYER
                && departure != null
                && Duration.between(now, departure).compareTo(lateCancellationWindow) < 0;
        long refund = late ? percentOf(charged, lateCancellationRefundPercent) : charged;
        long retained = charged - refund;
        long fee = percentOf(retained, platformFeePercent);
        return new Plan(refund, fee, retained - fee);
    }

    /** Arrondi au centime le plus proche, moitie vers le haut. */
    private static long percentOf(long amount, int percent) {
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}

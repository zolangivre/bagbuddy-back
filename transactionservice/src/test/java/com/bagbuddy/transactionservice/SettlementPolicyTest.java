package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.service.SettlementPolicy;
import com.bagbuddy.transactionservice.service.SettlementPolicy.Plan;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** La repartition de l'argent, sans Spring : remboursement + commission + versement = encaisse. */
class SettlementPolicyTest {

    private final SettlementPolicy policy = new SettlementPolicy(10, Duration.ofHours(24), 50);
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 11, 12, 0);

    private static void assertBalanced(Plan plan, long charged) {
        assertThat(plan.refund() + plan.platformFee() + plan.payout()).isEqualTo(charged);
    }

    @Test
    void aCompletedDealPaysTheTravellerMinusTheFee() {
        Plan plan = policy.onCompletion(2500);

        assertThat(plan).isEqualTo(new Plan(0, 250, 2250));
        assertBalanced(plan, 2500);
    }

    @Test
    void theTravellerCancellingRefundsEverything() {
        Plan plan = policy.onCancellation(2500, Actor.SELLER, now.plusHours(2), now);

        assertThat(plan).isEqualTo(new Plan(2500, 0, 0));
    }

    @Test
    void theBuyerCancellingEarlyIsRefundedInFull() {
        Plan plan = policy.onCancellation(2500, Actor.BUYER, now.plusHours(25), now);

        assertThat(plan).isEqualTo(new Plan(2500, 0, 0));
    }

    @Test
    void theBuyerCancellingLateGetsHalfBackAndTheTravellerIsCompensated() {
        Plan plan = policy.onCancellation(2500, Actor.BUYER, now.plusHours(3), now);

        // 1250 rembourses ; sur les 1250 retenus, 125 de commission et 1125 au voyageur.
        assertThat(plan).isEqualTo(new Plan(1250, 125, 1125));
        assertBalanced(plan, 2500);
    }

    @Test
    void anUnknownDepartureOrASystemCancellationRefundsEverything() {
        assertThat(policy.onCancellation(2500, Actor.BUYER, null, now)).isEqualTo(new Plan(2500, 0, 0));
        assertThat(policy.onCancellation(2500, Actor.SYSTEM, now.plusHours(1), now)).isEqualTo(new Plan(2500, 0, 0));
    }

    @Test
    void oddCentsStillAddUp() {
        Plan completed = policy.onCompletion(1999);
        Plan late = policy.onCancellation(1999, Actor.BUYER, now.plusHours(1), now);

        assertBalanced(completed, 1999);
        assertBalanced(late, 1999);
        assertThat(completed.platformFee()).isEqualTo(200);
    }
}

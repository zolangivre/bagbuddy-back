package com.bagbuddy.transactionservice.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Relance des remboursements et versements en attente : stripeservice injoignable au moment de la
 * transition, ou voyageur qui n'avait pas encore de compte de versement. Sans danger a rejouer --
 * stripeservice ne paie jamais deux fois la meme transaction.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bagbuddy.payments.settlement.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementJob {

    private final SettlementService settlementService;

    public SettlementJob(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(initialDelayString = "${bagbuddy.payments.settlement.initial-delay:PT1M}",
            fixedDelayString = "${bagbuddy.payments.settlement.interval:PT10M}")
    public void settlePending() {
        settlementService.settlePending();
    }
}

package com.bagbuddy.transactionservice.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Passe periodique d'expiration (TransactionService.expireDepartedRequests).
 *
 * Une seule instance de transactionservice tourne aujourd'hui. Le jour ou il y en a plusieurs,
 * chacune lancera la passe : c'est sans danger -- chaque transaction est reprise sous verrou et
 * revalidee -- mais du travail en double ; un verrou partage (ShedLock) le supprimera.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bagbuddy.transaction.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class TransactionExpiryJob {

    private final TransactionService transactionService;

    public TransactionExpiryJob(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Scheduled(initialDelayString = "${bagbuddy.transaction.expiry.initial-delay:PT2M}",
            fixedDelayString = "${bagbuddy.transaction.expiry.interval:PT15M}")
    public void expireDepartedRequests() {
        transactionService.expireDepartedRequests();
    }
}

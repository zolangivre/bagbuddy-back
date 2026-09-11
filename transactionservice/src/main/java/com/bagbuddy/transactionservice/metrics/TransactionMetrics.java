package com.bagbuddy.transactionservice.metrics;

import com.bagbuddy.transactionservice.notification.TransactionStatusChanged;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Le flux metier en chiffres : combien de reservations entrent dans chaque etat. Branche sur
 * l'evenement des notifications plutot que dans TransactionService, et compte apres le commit
 * seulement, comme les emails : une transition annulee n'est pas comptee.
 *
 * Les etiquettes sont les paires de statuts de TransactionStateMachine : une poignee de valeurs,
 * pas de risque d'exploser la cardinalite.
 */
@Component
public class TransactionMetrics {

    private final MeterRegistry meters;

    public TransactionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(TransactionStatusChanged event) {
        meters.counter("bagbuddy.transaction.transitions",
                        "seller_status", String.valueOf(event.sellerStatus()),
                        "buyer_status", String.valueOf(event.buyerStatus()),
                        "actor", event.actor() == null ? "none" : event.actor().name().toLowerCase(Locale.ROOT))
                .increment();
    }
}

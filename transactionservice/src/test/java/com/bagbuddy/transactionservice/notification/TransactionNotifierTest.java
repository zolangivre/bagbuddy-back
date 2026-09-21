package com.bagbuddy.transactionservice.notification;

import com.bagbuddy.transactionservice.config.TransactionStatusProperties;
import com.bagbuddy.transactionservice.notification.TransactionNotifier.Kind;
import com.bagbuddy.transactionservice.notification.TransactionStatusChanged.Party;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Qui est prevenu, sans contexte Spring : la regle tient dans noticesFor(). */
class TransactionNotifierTest {

    private final TransactionNotifier notifier =
            new TransactionNotifier(new TransactionStatusProperties(), mock(TransactionMailer.class), true);

    private static TransactionStatusChanged cancelled(Actor actor) {
        return new TransactionStatusChanged(7L, actor, "cancelled", "cancelled",
                new Party("buyer@example.com", "Camille"), new Party("seller@example.com", "Moussa"),
                "CDG", "DSS", "2026-10-03T23:30", new BigDecimal("2"));
    }

    @Test
    void anExpiryByTheSystemReachesBothParties() {
        assertThat(notifier.noticesFor(cancelled(Actor.SYSTEM)))
                .extracting(notice -> notice.kind() + ":" + notice.recipient().email())
                .containsExactlyInAnyOrder("EXPIRED:buyer@example.com", "EXPIRED:seller@example.com");
    }

    @Test
    void aCancellationByOnePartyReachesOnlyTheOther() {
        assertThat(notifier.noticesFor(cancelled(Actor.SELLER)))
                .singleElement()
                .satisfies(notice -> {
                    assertThat(notice.kind()).isEqualTo(Kind.CANCELLED);
                    assertThat(notice.recipient().email()).isEqualTo("buyer@example.com");
                });
    }
}

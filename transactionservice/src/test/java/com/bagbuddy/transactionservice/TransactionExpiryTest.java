package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les demandes jamais payees dont le vol est parti sont annulees par le planificateur. Appele ici
 * directement : la passe planifiee est coupee dans les proprietes de test.
 */
@SpringBootTest
class TransactionExpiryTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private TripClient tripClient;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
    }

    private Transaction deal(String sellerStatus, String buyerStatus, String departureDate) {
        ListingInfo listing = new ListingInfo();
        listing.setDepartureDate(departureDate);

        Transaction tx = new Transaction();
        tx.setBuyerId("buyer-sub");
        tx.setSellerId("seller-sub");
        tx.setListingId(7L);
        tx.setListingInfo(listing);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("20.00"));
        tx.setSellerStatus(sellerStatus);
        tx.setBuyerStatus(buyerStatus);
        return transactionRepository.save(tx);
    }

    /** Le format que listingInfoOf() ecrit : LocalDateTime.toString(). */
    private static String departed() {
        return LocalDateTime.now().minusHours(3).withSecond(0).withNano(0).toString();
    }

    private static String upcoming() {
        return LocalDateTime.now().plusDays(2).withSecond(0).withNano(0).toString();
    }

    private String pair(Transaction tx) {
        Transaction stored = transactionRepository.findById(tx.getId()).orElseThrow();
        return stored.getSellerStatus() + "/" + stored.getBuyerStatus();
    }

    @Test
    void unpaidDealsWhoseFlightHasLeftAreCancelled() {
        Transaction requested = deal("reservation_received", "waiting_for_response", departed());
        Transaction rejected = deal("waiting_for_response_seller", "request_rejected", departed());
        Transaction accepted = deal("awaiting_payment", "payment_required", departed());

        assertThat(transactionService.expireDepartedRequests()).isEqualTo(3);

        assertThat(pair(requested)).isEqualTo("cancelled/cancelled");
        assertThat(pair(rejected)).isEqualTo("cancelled/cancelled");
        assertThat(pair(accepted)).isEqualTo("cancelled/cancelled");
    }

    @Test
    void anAcceptedDealGivesItsWeightBackWhenItExpires() {
        Transaction accepted = deal("awaiting_payment", "payment_required", departed());
        Transaction requested = deal("reservation_received", "waiting_for_response", departed());

        transactionService.expireDepartedRequests();

        // Seule l'acceptation avait pris du poids : sans cette restitution, le voyageur ne
        // pourrait plus supprimer son annonce.
        verify(tripClient).release(7L, accepted.getId());
        verify(tripClient, never()).release(7L, requested.getId());
    }

    @Test
    void paidUpcomingOrUndatedDealsAreLeftAlone() {
        Transaction paid = deal("confirmed", "confirmed", departed());
        Transaction done = deal("completed", "completed", departed());
        Transaction upcoming = deal("reservation_received", "waiting_for_response", upcoming());
        Transaction undated = deal("reservation_received", "waiting_for_response", null);
        Transaction garbage = deal("reservation_received", "waiting_for_response", "");

        assertThat(transactionService.expireDepartedRequests()).isZero();

        assertThat(pair(paid)).isEqualTo("confirmed/confirmed");
        assertThat(pair(done)).isEqualTo("completed/completed");
        assertThat(pair(upcoming)).isEqualTo("reservation_received/waiting_for_response");
        assertThat(pair(undated)).isEqualTo("reservation_received/waiting_for_response");
        assertThat(pair(garbage)).isEqualTo("reservation_received/waiting_for_response");
        verify(tripClient, never()).release(any(), any());
    }

    @Test
    void aSecondRunFindsNothingLeftToDo() {
        deal("awaiting_payment", "payment_required", departed());

        assertThat(transactionService.expireDepartedRequests()).isEqualTo(1);
        assertThat(transactionService.expireDepartedRequests()).isZero();
    }

    @Test
    void aReleaseFailureDoesNotUndoTheExpiry() {
        Transaction accepted = deal("awaiting_payment", "payment_required", departed());
        when(tripClient.release(any(), any())).thenThrow(new IllegalStateException("tripservice down"));

        assertThat(transactionService.expireDepartedRequests()).isEqualTo(1);
        assertThat(pair(accepted)).isEqualTo("cancelled/cancelled");
    }
}

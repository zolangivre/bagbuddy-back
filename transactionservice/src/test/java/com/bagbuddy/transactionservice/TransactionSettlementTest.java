package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.StripeClient;
import com.bagbuddy.transactionservice.client.StripeClient.TransferResult;
import com.bagbuddy.transactionservice.client.StripeClient.TransferStatus;
import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.SettlementStatus;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.service.SettlementService;
import com.bagbuddy.transactionservice.web.ServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce qui arrive a l'argent quand une transaction payee se termine ou s'annule. stripeservice est
 * mocke : ce qui est verifie, ce sont les montants demandes, la tenue des statuts, et que rien ne
 * se perd quand stripeservice ne repond pas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionSettlementTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";
    private static final String SETTLEMENT_FIELDS =
            "sellerStatus platformFee refundAmount refundStatus payoutAmount payoutStatus";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StripeClient stripe;

    @MockitoBean
    private TripClient tripClient;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
    }

    /** Payee 25,00 EUR par Stripe, en attente de livraison. */
    private Transaction paidDeal(LocalDateTime departure) {
        ListingInfo listing = new ListingInfo();
        listing.setDepartureDate(departure.withNano(0).toString());

        Transaction tx = new Transaction();
        tx.setBuyerId(BUYER);
        tx.setSellerId(SELLER);
        tx.setListingId(1L);
        tx.setListingInfo(listing);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("25.00"));
        tx.setSellerStatus("confirmed");
        tx.setBuyerStatus("confirmed");
        tx.setStripePaymentIntentId("pi_1");
        tx.setStripeAmount(2500L);
        tx.setStripeCurrency("eur");
        tx.setPaidAt(LocalDateTime.now().minusDays(1));
        tx.setHandoverCode("123456");
        tx.setProhibitedItemsAccepted(true);
        return transactionRepository.save(tx);
    }

    private Transaction paidDeal() {
        return paidDeal(LocalDateTime.now().plusDays(5));
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/transactions/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder move(Long id, String status) throws Exception {
        return graphql("mutation($id: ID!, $input: UpdateTransactionInput!) { updateTransaction(id: $id, input: $input) { "
                        + SETTLEMENT_FIELDS + " } }",
                Map.of("id", id, "input", Map.of("sellerStatus", status, "buyerStatus", status)));
    }

    private Transaction stored(Transaction tx) {
        return transactionRepository.findById(tx.getId()).orElseThrow();
    }

    /** Le reglement s'execute apres le commit, sur un autre thread : on attend qu'il ait abouti. */
    private Transaction eventually(Transaction tx, Predicate<Transaction> settled) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        Transaction current = stored(tx);
        while (!settled.test(current) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            current = stored(tx);
        }
        return current;
    }

    @Test
    void theBuyerClosingAPaidDealPaysTheTravellerTheirShare() throws Exception {
        Transaction tx = paidDeal();
        when(stripe.transfer(tx.getId(), "pi_1", SELLER, 2250)).thenReturn(new TransferResult(TransferStatus.PAID, "tr_1"));

        mockMvc.perform(move(tx.getId(), "completed").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.updateTransaction.platformFee").value(250))
                .andExpect(jsonPath("$.data.updateTransaction.payoutAmount").value(2250));

        Transaction after = eventually(tx, t -> t.getPayoutStatus() == SettlementStatus.DONE);
        assertThat(after.getPayoutStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(after.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(after.getPaidOutAt()).isNotNull();
        verify(stripe, never()).refund(any(), any(), anyLong());
    }

    @Test
    void theHandoverCodePathPaysTheTravellerToo() throws Exception {
        Transaction tx = paidDeal();
        when(stripe.transfer(tx.getId(), "pi_1", SELLER, 2250)).thenReturn(new TransferResult(TransferStatus.PAID, "tr_2"));

        mockMvc.perform(graphql("mutation($id: ID!, $code: String!) { confirmHandover(id: $id, code: $code) { sellerStatus } }",
                        Map.of("id", tx.getId(), "code", "123456"))
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmHandover.sellerStatus").value("completed"));

        assertThat(eventually(tx, t -> t.getPayoutStatus() == SettlementStatus.DONE).getPayoutStatus())
                .isEqualTo(SettlementStatus.DONE);
    }

    @Test
    void theTravellerCancellingRefundsTheBuyerInFull() throws Exception {
        Transaction tx = paidDeal(LocalDateTime.now().plusHours(2));
        when(stripe.refund(tx.getId(), "pi_1", 2500)).thenReturn("re_1");

        mockMvc.perform(move(tx.getId(), "cancelled").with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.refundAmount").value(2500))
                .andExpect(jsonPath("$.data.updateTransaction.payoutAmount").value(0));

        Transaction after = eventually(tx, t -> t.getRefundStatus() == SettlementStatus.DONE);
        assertThat(after.getRefundStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(after.getStripeRefundId()).isEqualTo("re_1");
        verify(stripe, never()).transfer(any(), any(), any(), anyLong());
    }

    @Test
    void aLateBuyerCancellationSplitsTheMoney() throws Exception {
        Transaction tx = paidDeal(LocalDateTime.now().plusHours(3));
        when(stripe.refund(tx.getId(), "pi_1", 1250)).thenReturn("re_2");
        when(stripe.transfer(tx.getId(), "pi_1", SELLER, 1125)).thenReturn(new TransferResult(TransferStatus.PAID, "tr_3"));

        mockMvc.perform(move(tx.getId(), "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.refundAmount").value(1250))
                .andExpect(jsonPath("$.data.updateTransaction.platformFee").value(125))
                .andExpect(jsonPath("$.data.updateTransaction.payoutAmount").value(1125));

        Transaction after = eventually(tx, t -> t.getRefundStatus() == SettlementStatus.DONE
                && t.getPayoutStatus() == SettlementStatus.DONE);
        assertThat(after.getRefundStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(after.getPayoutStatus()).isEqualTo(SettlementStatus.DONE);
    }

    @Test
    void anOutageLeavesTheRefundPendingAndTheJobFinishesIt() throws Exception {
        Transaction tx = paidDeal();
        when(stripe.refund(tx.getId(), "pi_1", 2500))
                .thenThrow(new ServiceUnavailableException("stripeservice", new RuntimeException("down")));

        // L'annulation passe : l'argent n'est pas perdu, il est du.
        mockMvc.perform(move(tx.getId(), "cancelled").with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.sellerStatus").value("cancelled"))
                .andExpect(jsonPath("$.data.updateTransaction.refundStatus").value("PENDING"));

        // La tentative apres commit a eu lieu, et a echoue : le remboursement reste du.
        verify(stripe, timeout(2000)).refund(tx.getId(), "pi_1", 2500);
        Thread.sleep(100);
        assertThat(stored(tx).getRefundStatus()).isEqualTo(SettlementStatus.PENDING);

        org.mockito.Mockito.doReturn("re_3").when(stripe).refund(tx.getId(), "pi_1", 2500);
        assertThat(settlementService.settlePending()).isEqualTo(1);

        assertThat(stored(tx).getRefundStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(settlementService.settlePending()).isZero();
    }

    @Test
    void aTravellerWithoutPayoutAccountIsPaidOnceTheyHaveOne() throws Exception {
        Transaction tx = paidDeal();
        when(stripe.transfer(tx.getId(), "pi_1", SELLER, 2250)).thenReturn(new TransferResult(TransferStatus.AWAITING_ACCOUNT, null));

        mockMvc.perform(move(tx.getId(), "completed").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                // La reponse part avant l'appel a stripeservice : le versement y est encore PENDING.
                .andExpect(jsonPath("$.data.updateTransaction.payoutStatus").value("PENDING"));

        assertThat(eventually(tx, t -> t.getPayoutStatus() == SettlementStatus.AWAITING_ACCOUNT).getPayoutStatus())
                .isEqualTo(SettlementStatus.AWAITING_ACCOUNT);

        org.mockito.Mockito.doReturn(new TransferResult(TransferStatus.PAID, "tr_4"))
                .when(stripe).transfer(tx.getId(), "pi_1", SELLER, 2250);
        settlementService.settlePending();

        assertThat(stored(tx).getPayoutStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(stored(tx).getStripeTransferId()).isEqualTo("tr_4");
    }

    @Test
    void aRefusalIsMarkedFailedAndNotRetriedForever() throws Exception {
        Transaction tx = paidDeal();
        when(stripe.refund(tx.getId(), "pi_1", 2500)).thenThrow(new IllegalArgumentException("amount too large"));

        mockMvc.perform(move(tx.getId(), "cancelled").with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.sellerStatus").value("cancelled"));

        assertThat(eventually(tx, t -> t.getRefundStatus() == SettlementStatus.FAILED).getRefundStatus())
                .isEqualTo(SettlementStatus.FAILED);
        assertThat(settlementService.settlePending()).isZero();
    }

    @Test
    void aSimulatedPaymentIsSplitWithoutCallingStripe() throws Exception {
        Transaction tx = paidDeal();
        tx.setStripePaymentIntentId(null);
        tx.setStripeAmount(null);
        transactionRepository.save(tx);

        mockMvc.perform(move(tx.getId(), "completed").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.payoutAmount").value(2250))
                .andExpect(jsonPath("$.data.updateTransaction.payoutStatus").value("SIMULATED"));

        verify(stripe, never()).transfer(any(), any(), any(), anyLong());
    }

    @Test
    void anUnpaidCancellationHasNothingToSettle() throws Exception {
        Transaction tx = paidDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        tx.setPaidAt(null);
        tx.setStripePaymentIntentId(null);
        tx.setStripeAmount(null);
        transactionRepository.save(tx);

        mockMvc.perform(move(tx.getId(), "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.refundStatus").doesNotExist())
                .andExpect(jsonPath("$.data.updateTransaction.payoutStatus").doesNotExist());

        verify(stripe, never()).refund(any(), any(), anyLong());
    }
}

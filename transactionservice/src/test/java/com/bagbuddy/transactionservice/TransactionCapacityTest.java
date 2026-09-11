package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le poids d'une transaction est pris sur l'annonce a l'acceptation et doit y revenir des que la
 * transaction cesse de le tenir. tripservice est mocke : ce qui est verifie ici, c'est quand
 * transactionservice demande de reserver ou de rendre, et pour quelle transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Les tests coupent l'export des metriques par defaut ; celui-ci verifie justement /actuator/prometheus.
@AutoConfigureObservability(tracing = false)
class TransactionCapacityTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TripClient tripClient;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
    }

    private Transaction deal(String sellerStatus, String buyerStatus) {
        Transaction tx = new Transaction();
        tx.setBuyerId(BUYER);
        tx.setSellerId(SELLER);
        tx.setListingInfo(new ListingInfo());
        tx.setListingId(1L);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("25.00"));
        tx.setSellerStatus(sellerStatus);
        tx.setBuyerStatus(buyerStatus);
        return transactionRepository.save(tx);
    }

    private TripSnapshot listing(LocalDateTime departure) {
        TripSnapshot trip = new TripSnapshot();
        trip.setId(1L);
        trip.setUserId(SELLER);
        trip.setActive(true);
        trip.setPricePerKg(new BigDecimal("12.50"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setTotalWeightAvailable(new BigDecimal("20"));
        trip.setDepartureDate(departure);
        return trip;
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/transactions/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder move(Long id, String sellerStatus, String buyerStatus) throws Exception {
        return graphql("""
                mutation($id: ID!, $input: UpdateTransactionInput!) {
                    updateTransaction(id: $id, input: $input) { buyerStatus sellerStatus }
                }
                """, Map.of("id", id, "input", Map.of("sellerStatus", sellerStatus, "buyerStatus", buyerStatus)));
    }

    private String pair(Long id) {
        Transaction tx = transactionRepository.findById(id).orElseThrow();
        return tx.getSellerStatus() + "/" + tx.getBuyerStatus();
    }

    @Test
    void cancellingAnAcceptedBookingGivesItsWeightBack() throws Exception {
        Transaction tx = deal("awaiting_payment", "payment_required");

        mockMvc.perform(move(tx.getId(), "cancelled", "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.buyerStatus").value("cancelled"));

        verify(tripClient).release(1L, tx.getId());
    }

    @Test
    void cancellingAPaidBookingGivesItsWeightBack() throws Exception {
        Transaction tx = deal("confirmed", "confirmed");

        mockMvc.perform(move(tx.getId(), "cancelled", "cancelled").with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.sellerStatus").value("cancelled"));

        verify(tripClient).release(1L, tx.getId());
    }

    @Test
    void cancellingARequestThatNeverTookWeightReleasesNothing() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");

        mockMvc.perform(move(tx.getId(), "cancelled", "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());

        verify(tripClient, never()).release(any(), any());
    }

    @Test
    void aFailedReleaseDoesNotUndoTheCancellation() throws Exception {
        Transaction tx = deal("awaiting_payment", "payment_required");
        when(tripClient.release(any(), any())).thenThrow(new IllegalStateException("tripservice down"));

        // L'annulation est ecrite ; seul le poids reste pris, ce qui est la direction sure.
        mockMvc.perform(move(tx.getId(), "cancelled", "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.buyerStatus").value("cancelled"));

        assertThat(pair(tx.getId())).isEqualTo("cancelled/cancelled");

        // Le poids reste pris sans que personne ne le voie dans l'API : c'est la metrique qui
        // permet d'alerter. Exposee sans jeton pour le scrape Prometheus.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(
                                "bagbuddy_capacity_release_failures_total{application=\"transaction-service\",cause=\"cancellation\"")));
    }

    /**
     * Deux clics l'un apres l'autre : le second voit la transaction deja acceptee et ne rappelle
     * pas tripservice. Deux clics simultanes, eux, atteignent tous les deux reserve() -- avec le
     * meme transactionId, que tripservice ne decompte qu'une fois (TripReservationTest et
     * TripCapacityConcurrencyTest).
     */
    @Test
    void aSecondAcceptReservesForTheSameTransactionAndChangesNothing() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        when(tripClient.reserve(any(), any(), any())).thenReturn(listing(LocalDateTime.now().plusDays(5)));
        var seller = jwt().jwt(j -> j.subject(SELLER));

        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required").with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required").with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());

        verify(tripClient).reserve(eq(1L), any(), eq(tx.getId()));
        verify(tripClient, never()).release(any(), any());
        assertThat(pair(tx.getId())).isEqualTo("awaiting_payment/payment_required");
    }

    @Test
    void weightTakenForAnAcceptThatDidNotLandIsGivenBack() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        // Pendant que tripservice reserve, l'acheteur annule sa demande : la phase d'ecriture
        // trouve une transaction annulee et refuse l'acceptation.
        doAnswer(invocation -> {
            Transaction current = transactionRepository.findById(tx.getId()).orElseThrow();
            current.setSellerStatus("cancelled");
            current.setBuyerStatus("cancelled");
            transactionRepository.save(current);
            return listing(LocalDateTime.now().plusDays(5));
        }).when(tripClient).reserve(any(), any(), any());

        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        verify(tripClient).release(1L, tx.getId());
        assertThat(pair(tx.getId())).isEqualTo("cancelled/cancelled");
    }

    @Test
    void weightIsKeptWhenTheFailedAcceptLostTheRaceToAPaidBooking() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        // Un premier clic a accepte, l'acheteur a paye : le second clic echoue, mais le poids
        // appartient desormais a une transaction payee et ne doit pas etre rendu.
        doAnswer(invocation -> {
            Transaction current = transactionRepository.findById(tx.getId()).orElseThrow();
            current.setSellerStatus("confirmed");
            current.setBuyerStatus("confirmed");
            transactionRepository.save(current);
            return listing(LocalDateTime.now().plusDays(5));
        }).when(tripClient).reserve(any(), any(), any());

        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        verify(tripClient, never()).release(any(), any());
    }

    @Test
    void anAcceptAgainstAWeightThatChangedMeanwhileIsRefusedAndCompensated() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        doAnswer(invocation -> {
            Transaction current = transactionRepository.findById(tx.getId()).orElseThrow();
            current.setWeight(new BigDecimal("9"));
            transactionRepository.save(current);
            return listing(LocalDateTime.now().plusDays(5));
        }).when(tripClient).reserve(any(), any(), any());

        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        verify(tripClient).release(1L, tx.getId());
        assertThat(pair(tx.getId())).isEqualTo("reservation_received/waiting_for_response");
    }

    @Test
    void aListingWhoseDepartureHasPassedCannotBeBookedEvenIfStillFlaggedActive() throws Exception {
        // trip.active n'est recalcule qu'a l'ecriture de l'annonce : il est encore vrai ici.
        when(tripClient.fetch(any())).thenReturn(listing(LocalDateTime.now().minusHours(1)));

        mockMvc.perform(graphql("""
                        mutation($input: CreateTransactionInput!) { createTransaction(input: $input) { id } }
                        """, Map.of("input", Map.of("listingId", 1, "weight", 2)))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        assertThat(transactionRepository.findAll()).isEmpty();
    }

    @Test
    void anAcceptedOrPaidBookingMustBeCancelledBeforeItIsDeleted() throws Exception {
        Transaction accepted = deal("awaiting_payment", "payment_required");
        Transaction paid = deal("confirmed", "confirmed");
        Transaction done = deal("completed", "completed");
        var buyer = jwt().jwt(j -> j.subject(BUYER));
        String delete = "mutation($id: ID!) { deleteTransaction(id: $id) }";

        for (Transaction tx : new Transaction[] {accepted, paid}) {
            mockMvc.perform(graphql(delete, Map.of("id", tx.getId())).with(buyer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
            assertThat(transactionRepository.findById(tx.getId())).isPresent();
        }

        mockMvc.perform(graphql(delete, Map.of("id", done.getId())).with(buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteTransaction").value(true));
    }
}

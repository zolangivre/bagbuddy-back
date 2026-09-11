package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Securite testee sur la vraie surface HTTP : POST /transactions/graphql traverse toute la
 * chaine de filtres. Sans jeton on obtient un 401 ; avec un jeton mais sans droit, un 200
 * portant errors[].extensions.classification.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionSecurityTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";
    private static final String STRANGER = "stranger-sub";

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

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/transactions/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    /** mutation updateTransaction, la forme utilisee par presque tous les tests d'etat. */
    private MockHttpServletRequestBuilder move(Long id, Map<String, Object> input) throws Exception {
        return graphql("""
                mutation($id: ID!, $input: UpdateTransactionInput!) {
                    updateTransaction(id: $id, input: $input) { buyerStatus sellerStatus }
                }
                """, Map.of("id", id, "input", input));
    }

    private Transaction existingDeal() {
        UserInfo buyerInfo = new UserInfo();
        buyerInfo.setSub(BUYER);
        buyerInfo.setEmail("buyer@example.com");
        buyerInfo.setPhone("+33611111111");

        Transaction tx = new Transaction();
        tx.setBuyerId(BUYER);
        tx.setSellerId(SELLER);
        tx.setBuyerInfo(buyerInfo);
        tx.setListingInfo(new ListingInfo());
        tx.setListingId(1L);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("25.00"));
        tx.setSellerStatus("reservation_received");
        tx.setBuyerStatus("waiting_for_response");
        return transactionRepository.save(tx);
    }

    private TripSnapshot listing() {
        TripSnapshot.SellerInfo seller = new TripSnapshot.SellerInfo();
        seller.setSub(SELLER);
        seller.setEmail("seller@example.com");
        seller.setName("Seller");

        TripSnapshot trip = new TripSnapshot();
        trip.setId(1L);
        trip.setUserId(SELLER);
        trip.setUserInfo(seller);
        trip.setActive(true);
        trip.setPricePerKg(new BigDecimal("12.50"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setTotalWeightAvailable(new BigDecimal("20"));
        trip.setDepartureDate(LocalDateTime.now().plusDays(5));
        return trip;
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(graphql("{ myTransactions { id } }")).andExpect(status().isUnauthorized());
    }

    @Test
    void theListingQueryOnlyReturnsTheCallersOwnDeals() throws Exception {
        existingDeal();

        mockMvc.perform(graphql("{ myTransactions { id } }")
                        .with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myTransactions.length()").value(0));

        mockMvc.perform(graphql("{ myTransactions { id } }")
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myTransactions.length()").value(1));
    }

    @Test
    void aStrangerCannotReadOrDeleteSomebodyElsesDeal() throws Exception {
        Transaction tx = existingDeal();
        var stranger = jwt().jwt(j -> j.subject(STRANGER));

        mockMvc.perform(graphql("query($id: ID!) { transaction(id: $id) { id } }",
                        Map.of("id", tx.getId())).with(stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.transaction").doesNotExist());

        mockMvc.perform(graphql("mutation($id: ID!) { deleteTransaction(id: $id) }",
                        Map.of("id", tx.getId())).with(stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        mockMvc.perform(graphql("query($b: String!) { totalSpent(buyerId: $b) }",
                        Map.of("b", BUYER)).with(stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        assertThat(transactionRepository.findById(tx.getId())).isPresent();
    }

    @Test
    void theBuyerCannotAcceptTheirOwnBooking() throws Exception {
        Transaction tx = existingDeal();

        // reservation_received -> awaiting_payment est une transition reservee au vendeur.
        mockMvc.perform(move(tx.getId(), Map.of(
                        "sellerStatus", "awaiting_payment", "buyerStatus", "payment_required"))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        Transaction stored = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(stored.getSellerStatus()).isEqualTo("reservation_received");
    }

    @Test
    void aMoveOutsideTheStateMachineIsRefused() throws Exception {
        Transaction tx = existingDeal();

        // On ne saute pas de "demande recue" directement a "termine".
        mockMvc.perform(move(tx.getId(), Map.of(
                        "sellerStatus", "completed", "buyerStatus", "completed"))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("waiting_for_response");
    }

    @Test
    void theSellerAcceptingIsWhatTakesTheWeightOutOfTheListing() throws Exception {
        Transaction tx = existingDeal();
        when(tripClient.reserve(any(), any(), any())).thenReturn(listing());

        mockMvc.perform(move(tx.getId(), Map.of(
                        "sellerStatus", "awaiting_payment", "buyerStatus", "payment_required"))
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.buyerStatus").value("payment_required"));

        org.mockito.Mockito.verify(tripClient).reserve(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(w -> w.compareTo(new BigDecimal("2")) == 0),
                org.mockito.ArgumentMatchers.eq(tx.getId()));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("payment_required");
    }

    @Test
    void aBuyerCannotDeclareThemselvesPaidWithoutStripe() throws Exception {
        Transaction tx = existingDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        transactionRepository.save(tx);

        Map<String, Object> confirm = Map.of("sellerStatus", "confirmed", "buyerStatus", "confirmed");

        // require-stripe est actif par defaut : sans confirmation du webhook, on refuse.
        mockMvc.perform(move(tx.getId(), confirm).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getPaidAt()).isNull();

        // Une fois le paiement enregistre par le webhook signe, la transition passe.
        mockMvc.perform(post("/transactions/internal/" + tx.getId() + "/payment")
                        .with(jwt().jwt(j -> j.subject("stripeservice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentIntentId":"pi_ok","amount":2500,"currency":"eur"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(move(tx.getId(), confirm).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTransaction.buyerStatus").value("confirmed"));
    }

    @Test
    void theAmountIsPricedFromTheListingNotFromTheRequest() throws Exception {
        when(tripClient.fetch(any())).thenReturn(listing());

        mockMvc.perform(graphql("""
                        mutation($input: CreateTransactionInput!) {
                            createTransaction(input: $input) {
                                total buyerId sellerId sellerStatus buyerStatus paidAt
                            }
                        }
                        """, Map.of("input", Map.of("listingId", 1, "weight", 2)))
                        .with(jwt().jwt(j -> j.subject(BUYER).claim("email", "buyer@example.com"))))
                .andExpect(status().isOk())
                // 2 kg x 12.50 EUR : le montant vient de l'annonce, pas de l'appelant.
                .andExpect(jsonPath("$.data.createTransaction.total").value(25.00))
                .andExpect(jsonPath("$.data.createTransaction.buyerId").value(BUYER))
                .andExpect(jsonPath("$.data.createTransaction.sellerId").value(SELLER))
                .andExpect(jsonPath("$.data.createTransaction.sellerStatus").value("reservation_received"))
                .andExpect(jsonPath("$.data.createTransaction.buyerStatus").value("waiting_for_response"))
                .andExpect(jsonPath("$.data.createTransaction.paidAt").doesNotExist());

        // L'annonce est lue pour tarifer, mais la capacite n'est pas encore prise :
        // elle ne sort du stock que lorsque le vendeur accepte.
        org.mockito.Mockito.verify(tripClient).fetch(1L);
        org.mockito.Mockito.verify(tripClient, org.mockito.Mockito.never()).reserve(any(), any(), any());
    }

    @Test
    void theSchemaRefusesTheMoneyAndIdentityFieldsOutright() throws Exception {
        // CreateTransactionInput n'expose ni total, ni sellerId, ni paidAt : ce que l'ancien
        // controleur REST ignorait silencieusement est desormais rejete par le typage.
        Map<String, Object> forged = new HashMap<>();
        forged.put("listingId", 1);
        forged.put("weight", 2);
        forged.put("total", 0.01);
        forged.put("sellerId", "attacker");
        forged.put("paidAt", "2020-01-01T00:00:00");

        mockMvc.perform(graphql("""
                        mutation($input: CreateTransactionInput!) {
                            createTransaction(input: $input) { id }
                        }
                        """, Map.of("input", forged))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));

        assertThat(transactionRepository.findAll()).isEmpty();
        org.mockito.Mockito.verify(tripClient, org.mockito.Mockito.never()).fetch(any());
    }

    private MockHttpServletRequestBuilder recordPayment(Long id, long amount) {
        return post("/transactions/internal/" + id + "/payment")
                .with(jwt().jwt(j -> j.subject("stripeservice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentIntentId":"pi_test","amount":%d,"currency":"eur"}
                        """.formatted(amount));
    }

    @Test
    void aPaymentIsOnlyRecordedWhileTheDealAwaitsIt() throws Exception {
        // Encore au stade de la demande : un PaymentIntent paye maintenant pourrait couvrir
        // une reservation re-tarifee plus tard.
        Transaction tx = existingDeal();

        mockMvc.perform(recordPayment(tx.getId(), 2500)).andExpect(status().isBadRequest());
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getPaidAt()).isNull();
    }

    @Test
    void aPaymentForLessThanTheTotalIsNotRecorded() throws Exception {
        Transaction tx = existingDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        transactionRepository.save(tx);

        // 1,00 EUR pour un total de 25,00 EUR.
        mockMvc.perform(recordPayment(tx.getId(), 100)).andExpect(status().isBadRequest());
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getPaidAt()).isNull();

        mockMvc.perform(move(tx.getId(), Map.of("sellerStatus", "confirmed", "buyerStatus", "confirmed"))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
    }

    @Test
    void aRecordedPaymentThatNoLongerCoversTheTotalDoesNotConfirm() throws Exception {
        // Donnee heritee : un paiement enregistre pour un montant qui n'est plus le total.
        Transaction tx = existingDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        tx.setPaidAt(LocalDateTime.now());
        tx.setStripeAmount(100L);
        transactionRepository.save(tx);

        mockMvc.perform(move(tx.getId(), Map.of("sellerStatus", "confirmed", "buyerStatus", "confirmed"))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("payment_required");
    }

    @Test
    void paymentFieldsAreOnlyWritableThroughTheServiceRole() throws Exception {
        Transaction tx = existingDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        transactionRepository.save(tx);
        String payload = """
                {"paymentIntentId":"pi_forged","amount":2500,"currency":"eur"}
                """;

        mockMvc.perform(post("/transactions/internal/" + tx.getId() + "/payment")
                        .with(jwt().jwt(j -> j.subject(BUYER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getPaidAt()).isNull();

        mockMvc.perform(post("/transactions/internal/" + tx.getId() + "/payment")
                        .with(jwt().jwt(j -> j.subject("stripeservice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getPaidAt()).isNotNull();
    }
}

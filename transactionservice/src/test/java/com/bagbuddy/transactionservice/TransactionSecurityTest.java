package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    @MockitoBean
    private TripClient tripClient;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
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
        mockMvc.perform(get("/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void theListingEndpointOnlyReturnsTheCallersOwnDeals() throws Exception {
        existingDeal();

        mockMvc.perform(get("/transactions").with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/transactions").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aStrangerCannotReadOrDeleteSomebodyElsesDeal() throws Exception {
        Transaction tx = existingDeal();

        mockMvc.perform(get("/transactions/" + tx.getId()).with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/transactions/" + tx.getId()).with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/transactions/buyer/" + BUYER + "/total-spent")
                        .with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isForbidden());

        assertThat(transactionRepository.findById(tx.getId())).isPresent();
    }

    @Test
    void theBuyerCannotAcceptTheirOwnBooking() throws Exception {
        Transaction tx = existingDeal();

        // reservation_received -> awaiting_payment est une transition reservee au vendeur.
        mockMvc.perform(put("/transactions/" + tx.getId())
                        .with(jwt().jwt(j -> j.subject(BUYER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerStatus":"awaiting_payment","buyerStatus":"payment_required"}
                                """))
                .andExpect(status().isForbidden());

        Transaction stored = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(stored.getSellerStatus()).isEqualTo("reservation_received");
    }

    @Test
    void aMoveOutsideTheStateMachineIsRefused() throws Exception {
        Transaction tx = existingDeal();

        // On ne saute pas de "demande recue" directement a "termine".
        mockMvc.perform(put("/transactions/" + tx.getId())
                        .with(jwt().jwt(j -> j.subject(BUYER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerStatus":"completed","buyerStatus":"completed"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("waiting_for_response");
    }

    @Test
    void theSellerAcceptingIsWhatTakesTheWeightOutOfTheListing() throws Exception {
        Transaction tx = existingDeal();
        when(tripClient.reserve(any(), any())).thenReturn(listing());

        mockMvc.perform(put("/transactions/" + tx.getId())
                        .with(jwt().jwt(j -> j.subject(SELLER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerStatus":"awaiting_payment","buyerStatus":"payment_required"}
                                """))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(tripClient).reserve(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(w -> w.compareTo(new BigDecimal("2")) == 0));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("payment_required");
    }

    @Test
    void aBuyerCannotDeclareThemselvesPaidWithoutStripe() throws Exception {
        Transaction tx = existingDeal();
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        transactionRepository.save(tx);

        String confirm = """
                {"sellerStatus":"confirmed","buyerStatus":"confirmed","paidAt":"2020-01-01T00:00:00"}
                """;

        // require-stripe est actif par defaut : sans confirmation du webhook, on refuse.
        mockMvc.perform(put("/transactions/" + tx.getId())
                        .with(jwt().jwt(j -> j.subject(BUYER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm))
                .andExpect(status().isBadRequest());
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

        mockMvc.perform(put("/transactions/" + tx.getId())
                        .with(jwt().jwt(j -> j.subject(BUYER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerStatus").value("confirmed"));
    }

    @Test
    void theAmountIsPricedFromTheListingNotFromTheRequest() throws Exception {
        when(tripClient.fetch(any())).thenReturn(listing());

        mockMvc.perform(post("/transactions")
                        .with(jwt().jwt(j -> j.subject(BUYER).claim("email", "buyer@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"listingId":1,"weight":2,"total":0.01,"sellerId":"attacker",
                                 "buyerId":"someone-else","buyerStatus":"completed","paidAt":"2020-01-01T00:00:00"}
                                """))
                .andExpect(status().isOk())
                // 2 kg x 12.50 EUR, not the 0.01 the client asked for
                .andExpect(jsonPath("$.total").value(25.00))
                .andExpect(jsonPath("$.buyerId").value(BUYER))
                .andExpect(jsonPath("$.sellerId").value(SELLER))
                .andExpect(jsonPath("$.sellerStatus").value("reservation_received"))
                .andExpect(jsonPath("$.buyerStatus").value("waiting_for_response"))
                .andExpect(jsonPath("$.paidAt").doesNotExist());

        // L'annonce est lue pour tarifer, mais la capacite n'est pas encore prise :
        // elle ne sort du stock que lorsque le vendeur accepte.
        org.mockito.Mockito.verify(tripClient).fetch(1L);
        org.mockito.Mockito.verify(tripClient, org.mockito.Mockito.never()).reserve(any(), any());
    }

    @Test
    void paymentFieldsAreOnlyWritableThroughTheServiceRole() throws Exception {
        Transaction tx = existingDeal();
        String payload = """
                {"paymentIntentId":"pi_forged","amount":1,"currency":"eur"}
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

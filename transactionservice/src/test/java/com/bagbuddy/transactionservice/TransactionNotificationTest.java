package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.notification.TransactionMailer;
import com.bagbuddy.transactionservice.notification.TransactionNotifier.Kind;
import com.bagbuddy.transactionservice.notification.TransactionNotifier.Notice;
import com.bagbuddy.transactionservice.notification.TransactionStatusChanged;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Chaque etape d'une transaction previent l'autre partie par email, une fois l'ecriture validee.
 * Ce qui est verrouille : le bon destinataire pour chaque etape, jamais l'auteur du geste, rien
 * pour une transition refusee, et une panne SMTP qui ne fait jamais echouer la transition.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionNotificationTest {

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

    @MockitoBean
    private TransactionMailer mailer;

    @BeforeEach
    void reset() {
        transactionRepository.deleteAll();
    }

    private static UserInfo party(String sub, String email, String firstName) {
        UserInfo info = new UserInfo();
        info.setSub(sub);
        info.setEmail(email);
        info.setGiven_name(firstName);
        return info;
    }

    private Transaction deal(String sellerStatus, String buyerStatus) {
        ListingInfo listing = new ListingInfo();
        listing.setDepartureAirport("CDG");
        listing.setArrivalAirport("DSS");
        listing.setDepartureDate("2026-10-03T23:30");
        listing.setSellerUserInfo(party(SELLER, "seller@example.com", "Moussa"));

        Transaction tx = new Transaction();
        tx.setBuyerId(BUYER);
        tx.setSellerId(SELLER);
        tx.setBuyerInfo(party(BUYER, "buyer@example.com", "Camille"));
        tx.setListingInfo(listing);
        tx.setListingId(1L);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("25.00"));
        tx.setSellerStatus(sellerStatus);
        tx.setBuyerStatus(buyerStatus);
        return transactionRepository.save(tx);
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/transactions/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder move(Long id, String sellerStatus, String buyerStatus) throws Exception {
        return graphql("""
                mutation($id: ID!, $input: UpdateTransactionInput!) {
                    updateTransaction(id: $id, input: $input) { sellerStatus buyerStatus }
                }
                """, Map.of("id", id, "input", Map.of("sellerStatus", sellerStatus, "buyerStatus", buyerStatus)));
    }

    /** Le seul email parti : a qui, et pour quelle etape. */
    private Notice sentNotice() {
        ArgumentCaptor<Notice> notice = ArgumentCaptor.forClass(Notice.class);
        verify(mailer, timeout(2000)).send(notice.capture(), any());
        return notice.getValue();
    }

    private static TripSnapshot listing() {
        TripSnapshot trip = new TripSnapshot();
        trip.setId(1L);
        trip.setUserId(SELLER);
        trip.setActive(true);
        trip.setPricePerKg(new BigDecimal("12.50"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setTotalWeightAvailable(new BigDecimal("20"));
        trip.setDepartureDate(LocalDateTime.now().plusDays(10));
        TripSnapshot.SellerInfo seller = new TripSnapshot.SellerInfo();
        seller.setSub(SELLER);
        seller.setEmail("seller@example.com");
        seller.setGiven_name("Moussa");
        trip.setUserInfo(seller);
        return trip;
    }

    @Test
    void aNewRequestIsAnnouncedToTheSeller() throws Exception {
        when(tripClient.fetch(1L)).thenReturn(listing());

        mockMvc.perform(graphql("""
                        mutation($input: CreateTransactionInput!) { createTransaction(input: $input) { id } }
                        """, Map.of("input", Map.of("listingId", 1, "weight", 2)))
                        .with(jwt().jwt(j -> j.subject(BUYER)
                                .claim("email", "buyer@example.com").claim("given_name", "Camille"))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        Notice notice = sentNotice();
        assertThat(notice.kind()).isEqualTo(Kind.NEW_REQUEST);
        assertThat(notice.recipient().email()).isEqualTo("seller@example.com");
        assertThat(notice.counterpart().firstName()).isEqualTo("Camille");
    }

    @Test
    void anAcceptedRequestIsAnnouncedToTheBuyerNotToTheSellerWhoAcceptedIt() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        when(tripClient.reserve(any(), any(), any())).thenReturn(listing());

        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        Notice notice = sentNotice();
        assertThat(notice.kind()).isEqualTo(Kind.REQUEST_ACCEPTED);
        assertThat(notice.recipient().email()).isEqualTo("buyer@example.com");
    }

    @Test
    void aCancellationIsAnnouncedToWhoeverDidNotCancel() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");

        mockMvc.perform(move(tx.getId(), "cancelled", "cancelled").with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        Notice notice = sentNotice();
        assertThat(notice.kind()).isEqualTo(Kind.CANCELLED);
        assertThat(notice.recipient().email()).isEqualTo("seller@example.com");
    }

    @Test
    void aRefusedTransitionSendsNothing() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");

        // Un acheteur ne peut pas accepter sa propre demande.
        mockMvc.perform(move(tx.getId(), "awaiting_payment", "payment_required")
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        verify(mailer, after(300).never()).send(any(), any());
    }

    @Test
    void aReviewFlagWithoutAStatusChangeSendsNothing() throws Exception {
        Transaction tx = deal("completed", "completed");

        mockMvc.perform(graphql("""
                        mutation($id: ID!, $input: UpdateTransactionInput!) {
                            updateTransaction(id: $id, input: $input) { buyerReview }
                        }
                        """, Map.of("id", tx.getId(), "input", Map.of("buyerReview", true)))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verify(mailer, after(300).never()).send(any(), any());
    }

    @Test
    void aMailServerDownDoesNotFailTheTransition() throws Exception {
        Transaction tx = deal("reservation_received", "waiting_for_response");
        doThrow(new MailSendException("SMTP down")).when(mailer).send(any(), any());

        mockMvc.perform(move(tx.getId(), "waiting_for_response_seller", "request_rejected")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.updateTransaction.buyerStatus").value("request_rejected"));

        verify(mailer, timeout(2000)).send(any(), any());
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getBuyerStatus())
                .isEqualTo("request_rejected");
    }

    @Test
    void everyKindRendersInBothLanguagesWithALinkToTheTransaction() {
        TransactionMailer real = new TransactionMailer(mock(JavaMailSender.class),
                "BagBuddy <no-reply@bagbuddy.local>", "http://localhost:4200/");
        Transaction tx = deal("confirmed", "confirmed");
        TransactionStatusChanged event = TransactionStatusChanged.of(tx, Actor.BUYER);

        for (Kind kind : Kind.values()) {
            Notice notice = new Notice(kind, event.seller(), event.buyer());
            String body = real.body(notice, event);
            assertThat(body)
                    .contains("Bonjour Moussa", "Hello Moussa", "Camille", "CDG → DSS")
                    .contains("3 octobre 2026", "October 3, 2026")
                    .contains("http://localhost:4200/transaction-detail?transactionId=" + tx.getId());
            assertThat(real.subject(notice, event)).contains("CDG → DSS");
        }
    }
}

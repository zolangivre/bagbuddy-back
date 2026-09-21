package com.bagbuddy.transactionservice;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.notification.TransactionMailer;
import com.bagbuddy.transactionservice.notification.TransactionNotifier.Kind;
import com.bagbuddy.transactionservice.notification.TransactionNotifier.Notice;
import com.bagbuddy.transactionservice.repository.TransactionMessageRepository;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Declaration du contenu, code de remise et messagerie : ce que les deux parties savent l'une de
 * l'autre, et qui peut clore une transaction payee.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionHandoverTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";
    private static final String STRANGER = "stranger-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionMessageRepository messageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TripClient tripClient;

    @MockitoBean
    private TransactionMailer mailer;

    @BeforeEach
    void reset() {
        messageRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/transactions/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    /** Acceptee et deja payee par le webhook : le prochain pas de l'acheteur la confirme. */
    private Transaction paidAndAccepted() {
        Transaction tx = new Transaction();
        tx.setBuyerId(BUYER);
        tx.setSellerId(SELLER);
        ListingInfo listing = new ListingInfo();
        UserInfo seller = new UserInfo();
        seller.setEmail("seller@example.com");
        listing.setSellerUserInfo(seller);
        tx.setListingInfo(listing);
        UserInfo buyer = new UserInfo();
        buyer.setEmail("buyer@example.com");
        tx.setBuyerInfo(buyer);
        tx.setListingId(1L);
        tx.setWeight(new BigDecimal("2"));
        tx.setTotal(new BigDecimal("25.00"));
        tx.setSellerStatus("awaiting_payment");
        tx.setBuyerStatus("payment_required");
        tx.setPaidAt(LocalDateTime.now());
        tx.setStripeAmount(2500L);
        tx.setContentDescription("Deux livres");
        tx.setProhibitedItemsAccepted(true);
        return transactionRepository.save(tx);
    }

    private Transaction confirmed() throws Exception {
        Transaction tx = paidAndAccepted();
        mockMvc.perform(graphql("""
                        mutation($id: ID!, $i: UpdateTransactionInput!) { updateTransaction(id: $id, input: $i) { id } }
                        """, Map.of("id", tx.getId(), "i", Map.of("sellerStatus", "confirmed", "buyerStatus", "confirmed")))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors").doesNotExist());
        return transactionRepository.findById(tx.getId()).orElseThrow();
    }

    private MockHttpServletRequestBuilder handover(Long id, String code, String caller) throws Exception {
        return graphql("mutation($id: ID!, $c: String!) { confirmHandover(id: $id, code: $c) { sellerStatus buyerStatus } }",
                Map.of("id", id, "c", code)).with(jwt().jwt(j -> j.subject(caller)));
    }

    @Test
    void aBookingNeedsADeclaredContentAndTheProhibitedItemsList() throws Exception {
        TripSnapshot trip = new TripSnapshot();
        trip.setId(1L);
        trip.setUserId(SELLER);
        trip.setPricePerKg(new BigDecimal("12.50"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setDepartureDate(LocalDateTime.now().plusDays(5));
        when(tripClient.fetch(1L)).thenReturn(trip);
        String create = "mutation($i: CreateTransactionInput!) { createTransaction(input: $i) { contentDescription prohibitedItemsAccepted } }";
        var buyer = jwt().jwt(j -> j.subject(BUYER));

        Map<String, Object> blank = new HashMap<>(Map.of("listingId", 1, "weight", 2,
                "contentDescription", "   ", "prohibitedItemsAccepted", true));
        mockMvc.perform(graphql(create, Map.of("i", blank)).with(buyer))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("content_description_required"));

        Map<String, Object> refused = new HashMap<>(blank);
        refused.put("contentDescription", "Epices");
        refused.put("prohibitedItemsAccepted", false);
        mockMvc.perform(graphql(create, Map.of("i", refused)).with(buyer))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("prohibited_items_not_accepted"));
        assertThat(transactionRepository.findAll()).isEmpty();

        refused.put("prohibitedItemsAccepted", true);
        mockMvc.perform(graphql(create, Map.of("i", refused)).with(buyer))
                .andExpect(jsonPath("$.data.createTransaction.contentDescription").value("Epices"))
                .andExpect(jsonPath("$.data.createTransaction.prohibitedItemsAccepted").value(true));
    }

    @Test
    void theCodeAppearsAtPaymentAndOnlyTheBuyerSeesIt() throws Exception {
        Transaction tx = confirmed();
        assertThat(tx.getHandoverCode()).matches("\\d{6}");
        String query = "query($id: ID!) { transaction(id: $id) { handoverCode handoverLocked } }";

        mockMvc.perform(graphql(query, Map.of("id", tx.getId())).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.data.transaction.handoverCode").value(tx.getHandoverCode()));
        mockMvc.perform(graphql(query, Map.of("id", tx.getId())).with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.data.transaction.handoverCode").doesNotExist())
                .andExpect(jsonPath("$.data.transaction.handoverLocked").value(false));
    }

    @Test
    void theTravellerClosesOnlyWithTheRightCode() throws Exception {
        Transaction tx = confirmed();

        // Plus de raccourci : le voyageur ne peut pas se declarer livre sans le code.
        mockMvc.perform(graphql("""
                        mutation($id: ID!, $i: UpdateTransactionInput!) { updateTransaction(id: $id, input: $i) { id } }
                        """, Map.of("id", tx.getId(), "i", Map.of("sellerStatus", "completed", "buyerStatus", "completed")))
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        mockMvc.perform(handover(tx.getId(), tx.getHandoverCode(), BUYER))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        String wrong = tx.getHandoverCode().equals("000000") ? "111111" : "000000";
        mockMvc.perform(handover(tx.getId(), wrong, SELLER))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_handover_code"));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getHandoverAttempts()).isEqualTo(1);

        String spaced = tx.getHandoverCode().substring(0, 3) + " " + tx.getHandoverCode().substring(3);
        mockMvc.perform(handover(tx.getId(), spaced, SELLER))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.confirmHandover.sellerStatus").value("completed"));

        ArgumentCaptor<Notice> notice = ArgumentCaptor.forClass(Notice.class);
        verify(mailer, timeout(2000).atLeastOnce()).send(notice.capture(), any());
        assertThat(notice.getAllValues()).anySatisfy(n -> assertThat(n.kind()).isEqualTo(Kind.COMPLETED));
    }

    @Test
    void fiveWrongCodesLockTheHandover() throws Exception {
        Transaction tx = confirmed();
        String wrong = tx.getHandoverCode().equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(handover(tx.getId(), wrong, SELLER))
                    .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_handover_code"));
        }
        mockMvc.perform(handover(tx.getId(), wrong, SELLER))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("handover_locked"));
        // Bloque, meme le bon code ne passe plus : l'acheteur clot lui-meme.
        mockMvc.perform(handover(tx.getId(), tx.getHandoverCode(), SELLER))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("handover_locked"));
        assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getSellerStatus()).isEqualTo("confirmed");

        mockMvc.perform(graphql("query($id: ID!) { transaction(id: $id) { handoverLocked } }", Map.of("id", tx.getId()))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.data.transaction.handoverLocked").value(true));
    }

    @Test
    void messagesStayBetweenTheTwoParties() throws Exception {
        Transaction tx = paidAndAccepted();
        String send = "mutation($t: ID!, $b: String!) { sendTransactionMessage(transactionId: $t, body: $b) { id body mine } }";
        String list = "query($t: ID!, $a: ID) { transactionMessages(transactionId: $t, afterId: $a) { id body mine } }";

        mockMvc.perform(graphql(send, Map.of("t", tx.getId(), "b", "  Comptoir 12 a 18h ?  "))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.data.sendTransactionMessage.body").value("Comptoir 12 a 18h ?"))
                .andExpect(jsonPath("$.data.sendTransactionMessage.mine").value(true));
        mockMvc.perform(graphql(send, Map.of("t", tx.getId(), "b", "Parfait")).with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        mockMvc.perform(graphql(list, Map.of("t", tx.getId())).with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(jsonPath("$.data.transactionMessages.length()").value(2))
                .andExpect(jsonPath("$.data.transactionMessages[0].mine").value(false))
                .andExpect(jsonPath("$.data.transactionMessages[1].mine").value(true));

        Long first = messageRepository.findAll().stream().mapToLong(m -> m.getId()).min().orElseThrow();
        mockMvc.perform(graphql(list, Map.of("t", tx.getId(), "a", first)).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.data.transactionMessages.length()").value(1))
                .andExpect(jsonPath("$.data.transactionMessages[0].body").value("Parfait"));

        mockMvc.perform(graphql(list, Map.of("t", tx.getId())).with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        mockMvc.perform(graphql(send, Map.of("t", tx.getId(), "b", "   ")).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_message"));
    }

    @Test
    void aCancelledTransactionKeepsItsThreadButTakesNoNewMessage() throws Exception {
        Transaction tx = paidAndAccepted();
        tx.setSellerStatus("cancelled");
        tx.setBuyerStatus("cancelled");
        transactionRepository.save(tx);

        mockMvc.perform(graphql("mutation($t: ID!, $b: String!) { sendTransactionMessage(transactionId: $t, body: $b) { id } }",
                        Map.of("t", tx.getId(), "b", "Encore la ?")).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("conversation_closed"));
    }
}

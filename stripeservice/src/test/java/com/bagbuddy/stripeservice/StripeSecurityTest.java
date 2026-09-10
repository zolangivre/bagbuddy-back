package com.bagbuddy.stripeservice;

import com.bagbuddy.stripeservice.client.TransactionClient;
import com.bagbuddy.stripeservice.client.TransactionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Les chemins qui atteignent reellement l'API Stripe ne sont pas joues ici : ce qui est verifie,
 * c'est tout ce qui doit echouer avant, et le fait que le webhook -- seul endpoint REST restant
 * -- reste ouvert sans jeton mais ferme sans signature valide.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StripeSecurityTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionClient transactionClient;

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/stripe/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", variables)));
    }

    private TransactionSnapshot unpaidDeal() {
        TransactionSnapshot snapshot = new TransactionSnapshot();
        snapshot.setId(1L);
        snapshot.setBuyerId(BUYER);
        snapshot.setSellerId(SELLER);
        snapshot.setTotal(new BigDecimal("25.00"));
        return snapshot;
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(graphql("{ stripeConfig { publishableKey } }", Map.of()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(graphql("mutation($id: ID!) { createPaymentIntent(transactionId: $id) { clientSecret } }",
                        Map.of("id", 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePublishableKeyIsReadableByAnyAuthenticatedMember() throws Exception {
        mockMvc.perform(graphql("{ stripeConfig { publishableKey } }", Map.of())
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stripeConfig.publishableKey")
                        .value("pk_test_dummy_for_context_load"));
    }

    @Test
    void onlyTheBuyerMayPayForATransaction() throws Exception {
        when(transactionClient.fetchAsCaller(any(), anyString())).thenReturn(unpaidDeal());

        mockMvc.perform(graphql("""
                        mutation($id: ID!) { createPaymentIntent(transactionId: $id) { clientSecret } }
                        """, Map.of("id", 1)).with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
    }

    @Test
    void anAlreadyPaidTransactionIsNotChargedTwice() throws Exception {
        TransactionSnapshot paid = unpaidDeal();
        paid.setPaidAt(LocalDateTime.now().minusDays(1));
        when(transactionClient.fetchAsCaller(any(), anyString())).thenReturn(paid);

        mockMvc.perform(graphql("""
                        mutation($id: ID!) { createPaymentIntent(transactionId: $id) { clientSecret } }
                        """, Map.of("id", 1)).with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
    }

    @Test
    void theWebhookNeedsNoTokenButRefusesAnUnsignedPayload() throws Exception {
        // Ouvert : Stripe n'a pas de jeton porteur. Mais la signature, elle, est exigee.
        mockMvc.perform(post("/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=0,v1=forged")
                        .content("{\"type\":\"payment_intent.succeeded\"}"))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(transactionClient);
    }
}

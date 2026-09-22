package com.bagbuddy.stripeservice;

import com.bagbuddy.stripeservice.client.TransactionClient;
import com.bagbuddy.stripeservice.client.UserClient;
import com.bagbuddy.stripeservice.gateway.StripeGateway;
import com.bagbuddy.stripeservice.gateway.StripeGateway.ConnectedAccount;
import com.bagbuddy.stripeservice.gateway.StripeUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Remboursements, versements et onboarding Connect. Stripe est remplace par un mock du
 * StripeGateway : ce qui est verifie, c'est qui peut declencher quoi, et qu'on ne paie jamais
 * deux fois ni vers un compte qui ne peut pas recevoir.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StripePayoutTest {

    private static final String SELLER = "seller-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StripeGateway stripe;

    @MockitoBean
    private UserClient userClient;

    @MockitoBean
    private TransactionClient transactionClient;

    private MockHttpServletRequestBuilder internal(String path, Map<String, Object> body) throws Exception {
        return post("/stripe/internal/" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("transactionservice"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private MockHttpServletRequestBuilder graphql(String query) throws Exception {
        return post("/stripe/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query)));
    }

    private static Map<String, Object> transfer(long amount) {
        return Map.of("transactionId", 42, "paymentIntentId", "pi_1", "sellerSub", SELLER, "amount", amount);
    }

    @Test
    void moneyMovementsAreClosedToMemberTokens() throws Exception {
        mockMvc.perform(internal("refunds", Map.of("transactionId", 42, "paymentIntentId", "pi_1", "amount", 2500))
                        .with(jwt().jwt(j -> j.subject("buyer-sub"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(internal("transfers", transfer(2250)).with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isForbidden());

        verify(stripe, never()).refund(anyString(), anyLong(), anyString());
        verify(stripe, never()).transfer(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aRefundAlreadyIssuedForTheTransactionIsReturnedInsteadOfDoubled() throws Exception {
        when(stripe.findRefund("pi_1", "42")).thenReturn(Optional.of("re_existing"));

        mockMvc.perform(internal("refunds", Map.of("transactionId", 42, "paymentIntentId", "pi_1", "amount", 2500))
                        .with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value("re_existing"));

        verify(stripe, never()).refund(anyString(), anyLong(), anyString());
    }

    @Test
    void aNewRefundIsIssuedForTheRequestedAmount() throws Exception {
        when(stripe.findRefund("pi_1", "42")).thenReturn(Optional.empty());
        when(stripe.refund("pi_1", 1250, "42")).thenReturn("re_new");

        mockMvc.perform(internal("refunds", Map.of("transactionId", 42, "paymentIntentId", "pi_1", "amount", 1250))
                        .with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value("re_new"));
    }

    @Test
    void aSellerWithoutAReadyAccountIsNotPaidYet() throws Exception {
        when(stripe.findTransfer("transaction-42")).thenReturn(Optional.empty());
        when(userClient.payoutAccount(SELLER)).thenReturn(null);

        mockMvc.perform(internal("transfers", transfer(2250)).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_ACCOUNT"));

        // Compte cree mais onboarding inacheve : toujours pas.
        when(userClient.payoutAccount(SELLER)).thenReturn("acct_seller");
        when(stripe.account("acct_seller")).thenReturn(Optional.of(new ConnectedAccount(true, false, false)));

        mockMvc.perform(internal("transfers", transfer(2250)).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_ACCOUNT"));

        verify(stripe, never()).transfer(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aReadySellerIsPaidFromTheOriginalCharge() throws Exception {
        when(stripe.findTransfer("transaction-42")).thenReturn(Optional.empty());
        when(userClient.payoutAccount(SELLER)).thenReturn("acct_seller");
        when(stripe.account("acct_seller")).thenReturn(Optional.of(new ConnectedAccount(true, true, true)));
        when(stripe.latestChargeOf("pi_1")).thenReturn("ch_1");
        when(stripe.transfer("acct_seller", 2250, "eur", "ch_1", "transaction-42", "42")).thenReturn("tr_1");

        mockMvc.perform(internal("transfers", transfer(2250)).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.transferId").value("tr_1"));
    }

    @Test
    void aTransferAlreadyMadeForTheTransactionIsNeverRepeated() throws Exception {
        when(stripe.findTransfer("transaction-42")).thenReturn(Optional.of("tr_existing"));

        mockMvc.perform(internal("transfers", transfer(2250)).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.transferId").value("tr_existing"));

        verify(userClient, never()).payoutAccount(any());
        verify(stripe, never()).transfer(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void invalidAmountsAreRefusedAndStripeOutagesAskForARetry() throws Exception {
        mockMvc.perform(internal("refunds", Map.of("transactionId", 42, "paymentIntentId", "pi_1", "amount", 0))
                        .with(service()))
                .andExpect(status().isBadRequest());

        when(stripe.findRefund(anyString(), anyString())).thenThrow(new StripeUnavailableException(new RuntimeException("timeout")));
        mockMvc.perform(internal("refunds", Map.of("transactionId", 42, "paymentIntentId", "pi_1", "amount", 100))
                        .with(service()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void onboardingCreatesTheAccountOnceAndRecordsItInTheProfile() throws Exception {
        when(userClient.payoutAccount(SELLER)).thenReturn(null);
        when(stripe.createExpressAccount(SELLER, "seller@example.com", "FR")).thenReturn("acct_new");
        when(stripe.onboardingLink(eq("acct_new"), anyString(), anyString())).thenReturn("https://connect.stripe.com/setup/x");

        mockMvc.perform(graphql("mutation { startPayoutOnboarding { url } }")
                        .with(jwt().jwt(j -> j.subject(SELLER).claim("email", "seller@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startPayoutOnboarding.url").value("https://connect.stripe.com/setup/x"));

        verify(userClient).savePayoutAccount(SELLER, "acct_new");
        verify(stripe).onboardingLink("acct_new",
                "http://localhost:4200/account?payouts=retry", "http://localhost:4200/account?payouts=done");
    }

    @Test
    void onboardingResumesAnExistingAccountAndReplacesAnUnknownOne() throws Exception {
        var seller = jwt().jwt(j -> j.subject(SELLER).claim("email", "seller@example.com"));
        when(stripe.onboardingLink(anyString(), anyString(), anyString())).thenReturn("https://connect.stripe.com/setup/y");

        // Compte deja cree : on reprend le meme.
        when(userClient.payoutAccount(SELLER)).thenReturn("acct_seller");
        when(stripe.account("acct_seller")).thenReturn(Optional.of(new ConnectedAccount(false, false, false)));
        mockMvc.perform(graphql("mutation { startPayoutOnboarding { url } }").with(seller))
                .andExpect(status().isOk());
        verify(stripe, never()).createExpressAccount(anyString(), anyString(), anyString());

        // Un acct_ saisi a la main autrefois, inconnu de Stripe : remplace par un vrai compte.
        when(userClient.payoutAccount(SELLER)).thenReturn("acct_typed_by_hand");
        when(stripe.account("acct_typed_by_hand")).thenReturn(Optional.empty());
        when(stripe.createExpressAccount(SELLER, "seller@example.com", "FR")).thenReturn("acct_real");
        mockMvc.perform(graphql("mutation { startPayoutOnboarding { url } }").with(seller))
                .andExpect(status().isOk());
        verify(userClient).savePayoutAccount(SELLER, "acct_real");
    }

    @Test
    void theCallerReadsOnlyTheirOwnAccountStatus() throws Exception {
        when(userClient.payoutAccount(SELLER)).thenReturn("acct_seller");
        when(stripe.account("acct_seller")).thenReturn(Optional.of(new ConnectedAccount(true, true, true)));

        mockMvc.perform(graphql("{ payoutAccount { connected detailsSubmitted payoutsEnabled transfersActive } }")
                        .with(jwt().jwt(j -> j.subject(SELLER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payoutAccount.connected").value(true))
                .andExpect(jsonPath("$.data.payoutAccount.transfersActive").value(true));

        mockMvc.perform(graphql("{ payoutAccount { connected } }")).andExpect(status().isUnauthorized());
        verify(userClient).payoutAccount(SELLER);
    }
}

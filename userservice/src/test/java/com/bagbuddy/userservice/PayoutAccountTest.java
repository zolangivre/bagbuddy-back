package com.bagbuddy.userservice;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.repository.UserRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le compte de versement d'un membre decide ou part l'argent de ses trajets : seul stripeservice
 * l'ecrit, apres l'avoir cree chez Stripe. Un membre ne peut ni le saisir ni le lire d'un autre.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayoutAccountTest {

    private static final String ALICE = "alice-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    @BeforeEach
    void reset() {
        userRepository.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("stripeservice")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    @Test
    void theInternalEndpointIsClosedToMembers() throws Exception {
        mockMvc.perform(get("/users/internal/" + ALICE + "/payout-account").with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/users/internal/" + ALICE + "/payout-account")
                        .with(jwt().jwt(j -> j.subject(ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeAccountId\":\"acct_attacker\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/users/internal/" + ALICE + "/payout-account"))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findBySub(ALICE)).isEmpty();
    }

    @Test
    void stripeServiceRecordsAndReadsTheAccount_creatingTheProfileIfNeeded() throws Exception {
        mockMvc.perform(get("/users/internal/" + ALICE + "/payout-account").with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeAccountId").doesNotExist());

        mockMvc.perform(put("/users/internal/" + ALICE + "/payout-account")
                        .with(service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeAccountId\":\"acct_1AbC\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/users/internal/" + ALICE + "/payout-account").with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeAccountId").value("acct_1AbC"));
        assertThat(userRepository.findBySub(ALICE).orElseThrow().getStripeAccountId()).isEqualTo("acct_1AbC");
    }

    @Test
    void somethingThatIsNotAStripeAccountIdIsRefused() throws Exception {
        mockMvc.perform(put("/users/internal/" + ALICE + "/payout-account")
                        .with(service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stripeAccountId\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void membersCanNoLongerTypeTheirOwnAccountId() throws Exception {
        mockMvc.perform(post("/users/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "mutation($i: UpdateProfileInput!) { updateProfile(input: $i) { bio } }",
                                "variables", Map.of("i", Map.of("stripeAccountId", "acct_typed")))))
                        .with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));
    }
}

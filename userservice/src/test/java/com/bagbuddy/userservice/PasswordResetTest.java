package com.bagbuddy.userservice;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.client.KeycloakUser;
import com.bagbuddy.userservice.repository.AccountTokenRepository;
import com.bagbuddy.userservice.service.AccountMailer;
import com.bagbuddy.userservice.web.AccountException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mot de passe oublie : deux operations anonymes de plus sur le seul schema ouvert sans jeton.
 * Ce qui est verrouille ici, c'est ce qu'un inconnu peut en tirer : ni savoir qui est inscrit,
 * ni inonder une boite mail, ni reutiliser un lien, ni se servir d'un lien parti vers une
 * adresse que le compte n'a plus.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetTest {

    private static final String ALICE = "alice-sub";
    private static final KeycloakUser ALICE_ACCOUNT =
            new KeycloakUser(ALICE, "alice@example.com", "Alice", true, false);

    private static final String REQUEST =
            "mutation($i: RequestPasswordResetInput!) { requestPasswordReset(input: $i) }";
    private static final String RESET =
            "mutation($i: ResetPasswordInput!) { resetPassword(input: $i) }";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountTokenRepository tokens;

    @MockitoBean
    private KeycloakAdminClient keycloak;

    @MockitoBean
    private AccountMailer mailer;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
    }

    private ResultActions perform(String query, Map<String, Object> input) throws Exception {
        return mockMvc.perform(post("/users/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", Map.of("i", input)))));
    }

    /** Demande un lien pour Alice et renvoie le jeton que l'email aurait porte. */
    private String requestAliceLink() throws Exception {
        when(keycloak.findUserByEmail("alice@example.com")).thenReturn(Optional.of(ALICE_ACCOUNT));
        perform(REQUEST, Map.of("email", "Alice@Example.com", "language", "fr"))
                .andExpect(jsonPath("$.data.requestPasswordReset").value(true));

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(2000)).sendPasswordReset(
                eq("alice@example.com"), eq("Alice"), link.capture(), any(), eq("fr"));
        // Le jeton voyage dans le fragment : il n'atteint jamais les journaux d'un serveur.
        assertThat(link.getValue()).startsWith("http://localhost:4200/reset-password#");
        return link.getValue().substring(link.getValue().indexOf('#') + 1);
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        when(keycloak.findUserByEmail(anyString())).thenReturn(Optional.empty());

        perform(REQUEST, Map.of("email", "nobody@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.requestPasswordReset").value(true));

        verify(keycloak, timeout(2000)).findUserByEmail("nobody@example.com");
        verify(mailer, after(300).never()).sendPasswordReset(any(), any(), any(), any(), any());
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    void aDisabledAccountGetsNoEmail() throws Exception {
        when(keycloak.findUserByEmail("alice@example.com"))
                .thenReturn(Optional.of(new KeycloakUser(ALICE, "alice@example.com", "Alice", false, false)));

        perform(REQUEST, Map.of("email", "alice@example.com"))
                .andExpect(jsonPath("$.data.requestPasswordReset").value(true));

        verify(keycloak, timeout(2000)).findUserByEmail("alice@example.com");
        verify(mailer, after(300).never()).sendPasswordReset(any(), any(), any(), any(), any());
    }

    @Test
    void repeatedRequestsDoNotFloodTheMailbox() throws Exception {
        requestAliceLink();

        perform(REQUEST, Map.of("email", "alice@example.com"))
                .andExpect(jsonPath("$.data.requestPasswordReset").value(true));

        verify(keycloak, timeout(2000).times(2)).findUserByEmail("alice@example.com");
        verify(mailer, after(300).times(1)).sendPasswordReset(any(), any(), any(), any(), any());
        assertThat(tokens.findAll()).hasSize(1);
    }

    @Test
    void theLinkSetsThePasswordOnceAndEndsOpenSessions() throws Exception {
        String token = requestAliceLink();
        // Le jeton n'est jamais stocke en clair.
        assertThat(tokens.findAll()).singleElement()
                .satisfies(stored -> assertThat(stored.getTokenHash()).isNotEqualTo(token));
        when(keycloak.findUser(ALICE)).thenReturn(Optional.of(ALICE_ACCOUNT));

        perform(RESET, Map.of("token", token, "newPassword", "nouveaumotdepasse"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.resetPassword").value(true));

        verify(keycloak).resetPassword(ALICE, "nouveaumotdepasse");
        verify(keycloak).logout(ALICE);

        perform(RESET, Map.of("token", token, "newPassword", "encoreunautre"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_reset_token"));
        verify(keycloak, never()).resetPassword(ALICE, "encoreunautre");
    }

    @Test
    void aLinkSentToAnAddressTheAccountNoLongerHasIsRefused() throws Exception {
        String token = requestAliceLink();
        when(keycloak.findUser(ALICE))
                .thenReturn(Optional.of(new KeycloakUser(ALICE, "alice@new.example.com", "Alice", true, false)));

        perform(RESET, Map.of("token", token, "newPassword", "nouveaumotdepasse"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_reset_token"));

        verify(keycloak, never()).resetPassword(any(), any());
    }

    @Test
    void aPasswordRefusedByTheRealmDoesNotCostTheLink() throws Exception {
        String token = requestAliceLink();
        when(keycloak.findUser(ALICE)).thenReturn(Optional.of(ALICE_ACCOUNT));
        doThrow(new AccountException(HttpStatus.BAD_REQUEST, "password_rejected", "Too weak"))
                .when(keycloak).resetPassword(ALICE, "motdepasse");

        perform(RESET, Map.of("token", token, "newPassword", "motdepasse"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("password_rejected"));

        perform(RESET, Map.of("token", token, "newPassword", "unbienmeilleur"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.resetPassword").value(true));
    }

    @Test
    void anUnknownTokenIsRefused() throws Exception {
        perform(RESET, Map.of("token", "pas-un-vrai-jeton", "newPassword", "nouveaumotdepasse"))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_reset_token"));

        verifyNoInteractions(keycloak);
    }
}

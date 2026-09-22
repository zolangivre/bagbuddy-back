package com.bagbuddy.userservice;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.client.KeycloakUser;
import com.bagbuddy.userservice.model.User;
import com.bagbuddy.userservice.repository.AccountTokenRepository;
import com.bagbuddy.userservice.repository.UserRepository;
import com.bagbuddy.userservice.service.AccountMailer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Verification d'email : l'envoi est reserve au titulaire connecte et part toujours vers
 * l'adresse que Keycloak connait ; la verification est anonyme mais ne vaut que pour cette
 * adresse, une seule fois.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationTest {

    private static final String ALICE = "alice-sub";
    private static final KeycloakUser UNVERIFIED =
            new KeycloakUser(ALICE, "alice@example.com", "Alice", true, false);

    private static final String SEND = "mutation($l: String) { sendVerificationEmail(language: $l) }";
    private static final String VERIFY = "mutation($t: String!) { verifyEmail(token: $t) }";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountTokenRepository tokens;

    @Autowired
    private UserRepository users;

    @MockitoBean
    private KeycloakAdminClient keycloak;

    @MockitoBean
    private AccountMailer mailer;

    @BeforeEach
    void reset() {
        tokens.deleteAll();
        users.deleteAll();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/users/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    /** Alice demande un lien ; renvoie le jeton que l'email aurait porte. */
    private String sendAliceLink() throws Exception {
        when(keycloak.findUser(ALICE)).thenReturn(Optional.of(UNVERIFIED));
        mockMvc.perform(graphql(SEND, Map.of("l", "fr"))
                        // Le jeton porte encore l'ancienne adresse : elle ne doit pas compter.
                        .with(jwt().jwt(j -> j.subject(ALICE).claim("email", "old@example.com"))))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.sendVerificationEmail").value(true));

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendEmailVerification(eq("alice@example.com"), eq("Alice"), link.capture(),
                any(), eq("fr"));
        assertThat(link.getValue()).startsWith("http://localhost:4200/verify-email#");
        return link.getValue().substring(link.getValue().indexOf('#') + 1);
    }

    @Test
    void sendingRequiresASignedInCaller() throws Exception {
        mockMvc.perform(graphql(SEND, Map.of()))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"));
        verifyNoInteractions(keycloak, mailer);
    }

    @Test
    void anAlreadyVerifiedAddressGetsNoEmail() throws Exception {
        when(keycloak.findUser(ALICE))
                .thenReturn(Optional.of(new KeycloakUser(ALICE, "alice@example.com", "Alice", true, true)));

        mockMvc.perform(graphql(SEND, Map.of()).with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(jsonPath("$.data.sendVerificationEmail").value(false));
        verifyNoInteractions(mailer);
    }

    @Test
    void aSecondEmailWithinAMinuteIsRefusedWithACode() throws Exception {
        sendAliceLink();

        mockMvc.perform(graphql(SEND, Map.of()).with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("verification_email_throttled"));
        verify(mailer, times(1)).sendEmailVerification(any(), any(), any(), any(), any());
    }

    @Test
    void anEmailThatCannotBeSentLeavesNoTokenBehind() throws Exception {
        when(keycloak.findUser(ALICE)).thenReturn(Optional.of(UNVERIFIED));
        doThrow(new MailSendException("SMTP down")).when(mailer)
                .sendEmailVerification(any(), any(), any(), any(), any());

        mockMvc.perform(graphql(SEND, Map.of()).with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("service_unavailable"));

        // Rien n'a ete envoye : l'intervalle minimal ne doit pas empecher de reessayer.
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    void theLinkVerifiesTheAddressOnceWithoutAnAccessToken() throws Exception {
        User profile = new User();
        profile.setSub(ALICE);
        profile.setEmail("alice@example.com");
        users.save(profile);
        String token = sendAliceLink();

        mockMvc.perform(graphql(VERIFY, Map.of("t", token)))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.verifyEmail").value(true));

        verify(keycloak).markEmailVerified(ALICE);
        // Les autres membres voient le badge sans attendre le prochain `me` d'Alice.
        assertThat(users.findBySub(ALICE)).hasValueSatisfying(user ->
                assertThat(user.isEmailVerified()).isTrue());

        mockMvc.perform(graphql(VERIFY, Map.of("t", token)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_verification_token"));
    }

    @Test
    void aLinkSentToAnAddressTheAccountNoLongerHasIsRefused() throws Exception {
        String token = sendAliceLink();
        when(keycloak.findUser(ALICE))
                .thenReturn(Optional.of(new KeycloakUser(ALICE, "alice@new.example.com", "Alice", true, false)));

        mockMvc.perform(graphql(VERIFY, Map.of("t", token)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_verification_token"));
        verify(keycloak, never()).markEmailVerified(any());
    }

    @Test
    void aPasswordResetTokenDoesNotVerifyAnEmail() throws Exception {
        when(keycloak.findUserByEmail("alice@example.com")).thenReturn(Optional.of(UNVERIFIED));
        mockMvc.perform(graphql(
                "mutation($i: RequestPasswordResetInput!) { requestPasswordReset(input: $i) }",
                Map.of("i", Map.of("email", "alice@example.com"))));
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(2000)).sendPasswordReset(any(), any(), link.capture(), any(), any());
        String resetToken = link.getValue().substring(link.getValue().indexOf('#') + 1);

        mockMvc.perform(graphql(VERIFY, Map.of("t", resetToken)))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_verification_token"));
        verify(keycloak, never()).markEmailVerified(any());
    }
}

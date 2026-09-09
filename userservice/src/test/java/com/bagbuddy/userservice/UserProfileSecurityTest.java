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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * userservice est le seul service dont le point d'entree GraphQL est ouvert sans jeton, parce
 * que l'inscription fait partie du schema. L'authentification y est donc portee par
 * @PreAuthorize resolver par resolver, et c'est fragile par nature : une operation ajoutee sans
 * annotation serait anonyme. anonymousCallersAreRejected passe en revue toutes les operations
 * du schema pour verrouiller cette regle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileSecurityTest {

    private static final String ALICE = "alice-sub";
    private static final String BOB = "bob-sub";

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

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/users/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    private void signInAlice() throws Exception {
        mockMvc.perform(graphql("{ me { sub email username } }")
                        .with(jwt().jwt(j -> j.subject(ALICE)
                                .claim("email", "alice@example.com")
                                .claim("preferred_username", "alice")
                                .claim("name", "Alice Martin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        // Le point d'entree repond 200 (il est ouvert pour l'inscription) : le refus se lit
        // donc dans la classification de l'erreur, pas dans le code HTTP.
        Map<String, Map<String, Object>> operations = new HashMap<>();
        operations.put("{ me { sub } }", Map.of());
        operations.put("query($s: String!) { user(sub: $s) { sub } }", Map.of("s", ALICE));
        operations.put("mutation($i: UpdateProfileInput!) { updateProfile(input: $i) { sub } }",
                Map.of("i", Map.of("bio", "anonyme")));
        operations.put("mutation($i: UpdateIdentityInput!) { updateIdentity(input: $i) { sub } }",
                Map.of("i", Map.of("firstName", "A", "lastName", "B", "email", "a@b.com")));
        operations.put("mutation($i: ChangePasswordInput!) { changePassword(input: $i) }",
                Map.of("i", Map.of("currentPassword", "x", "newPassword", "motdepasse123")));

        for (Map.Entry<String, Map<String, Object>> operation : operations.entrySet()) {
            mockMvc.perform(graphql(operation.getKey(), operation.getValue()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errors[0].extensions.classification")
                            .value("UNAUTHORIZED"));
        }

        assertThat(userRepository.findAll()).isEmpty();
    }

    @Test
    void registrationIsTheOneOperationOpenWithoutAToken() throws Exception {
        mockMvc.perform(graphql("mutation($i: RegisterInput!) { register(input: $i) }",
                        Map.of("i", Map.of(
                                "firstName", "Alice",
                                "lastName", "Martin",
                                "email", "Alice@Example.com",
                                "password", "motdepasse123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.register").value(true));

        // L'email sert d'identifiant : il est normalise avant d'atteindre Keycloak.
        org.mockito.Mockito.verify(keycloakAdminClient)
                .createUser(eq("alice@example.com"), eq("Alice"), eq("Martin"), eq("motdepasse123"));
    }

    @Test
    void aWeakPasswordIsRefusedBeforeReachingKeycloak() throws Exception {
        mockMvc.perform(graphql("mutation($i: RegisterInput!) { register(input: $i) }",
                        Map.of("i", Map.of(
                                "firstName", "Alice",
                                "lastName", "Martin",
                                "email", "alice@example.com",
                                "password", "court"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        org.mockito.Mockito.verifyNoInteractions(keycloakAdminClient);
    }

    @Test
    void theProfileIsProvisionedFromTheTokenOnFirstCall() throws Exception {
        signInAlice();

        assertThat(userRepository.findBySub(ALICE)).hasValueSatisfying(user -> {
            assertThat(user.getEmail()).isEqualTo("alice@example.com");
            assertThat(user.getUsername()).isEqualTo("alice");
        });
    }

    @Test
    void identityFieldsCannotBeRewrittenThroughTheProfileMutation() throws Exception {
        signInAlice();
        var alice = jwt().jwt(j -> j.subject(ALICE)
                .claim("email", "alice@example.com")
                .claim("preferred_username", "alice"));

        // UpdateProfileInput n'expose ni email, ni username, ni sub : l'usurpation est refusee
        // par le typage, avant tout resolver.
        Map<String, Object> forged = new HashMap<>();
        forged.put("bio", "Voyage souvent");
        forged.put("email", "attacker@example.com");
        forged.put("username", "admin");
        forged.put("sub", BOB);

        mockMvc.perform(graphql("mutation($i: UpdateProfileInput!) { updateProfile(input: $i) { bio } }",
                        Map.of("i", forged)).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));

        // Le champ legitime, lui, passe -- et l'identite reste celle du jeton.
        mockMvc.perform(graphql("""
                        mutation($i: UpdateProfileInput!) {
                            updateProfile(input: $i) { bio email username }
                        }
                        """, Map.of("i", Map.of("bio", "Voyage souvent"))).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateProfile.bio").value("Voyage souvent"))
                .andExpect(jsonPath("$.data.updateProfile.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.updateProfile.username").value("alice"));

        assertThat(userRepository.findBySub(BOB)).isEmpty();
    }

    @Test
    void anotherMembersProfileHidesContactDetails() throws Exception {
        signInAlice();
        mockMvc.perform(graphql("mutation($i: UpdateProfileInput!) { updateProfile(input: $i) { bio } }",
                        Map.of("i", Map.of(
                                "phone", "+33600000000",
                                "stripeAccountId", "acct_alice",
                                "bio", "Bonjour")))
                        // Les claims d'identite sont remirrorees a chaque appel : un jeton
                        // ampute les effacerait, comme le ferait un vrai jeton incomplet.
                        .with(jwt().jwt(j -> j.subject(ALICE)
                                .claim("email", "alice@example.com")
                                .claim("name", "Alice Martin"))))
                .andExpect(status().isOk());

        // Le type PublicUserProfile ne porte pas ces champs : les demander est une erreur de
        // schema, ce qui est une garantie plus forte qu'un champ absent de la reponse.
        mockMvc.perform(graphql("query($s: String!) { user(sub: $s) { bio email } }",
                        Map.of("s", ALICE)).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));

        mockMvc.perform(graphql("query($s: String!) { user(sub: $s) { bio name } }",
                        Map.of("s", ALICE)).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.bio").value("Bonjour"))
                .andExpect(jsonPath("$.data.user.name").value("Alice Martin"));
    }

    @Test
    void theOwnerStillSeesTheirOwnContactDetails() throws Exception {
        signInAlice();

        mockMvc.perform(graphql("{ me { email } }")
                        .with(jwt().jwt(j -> j.subject(ALICE).claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.email").value("alice@example.com"));
    }
}

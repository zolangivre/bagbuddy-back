package com.bagbuddy.userservice;

import com.bagbuddy.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserProfileSecurityTest {

    private static final String ALICE = "alice-sub";
    private static final String BOB = "bob-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void reset() {
        userRepository.deleteAll();
    }

    private void signInAlice() throws Exception {
        mockMvc.perform(get("/users/me").with(jwt().jwt(j -> j.subject(ALICE)
                        .claim("email", "alice@example.com")
                        .claim("preferred_username", "alice")
                        .claim("name", "Alice Martin"))))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/users/" + ALICE)).andExpect(status().isUnauthorized());
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
    void identityFieldsCannotBeRewrittenThroughTheProfileEndpoint() throws Exception {
        signInAlice();

        mockMvc.perform(put("/users/me")
                        .with(jwt().jwt(j -> j.subject(ALICE)
                                .claim("email", "alice@example.com")
                                .claim("preferred_username", "alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio":"Voyage souvent","email":"attacker@example.com",
                                 "username":"admin","sub":"bob-sub"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Voyage souvent"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.username").value("alice"));

        assertThat(userRepository.findBySub(BOB)).isEmpty();
    }

    @Test
    void anotherMembersProfileHidesContactDetails() throws Exception {
        signInAlice();
        mockMvc.perform(put("/users/me")
                        .with(jwt().jwt(j -> j.subject(ALICE).claim("email", "alice@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"+33600000000","stripeAccountId":"acct_alice","bio":"Bonjour"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/" + ALICE).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Bonjour"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.stripeAccountId").doesNotExist());
    }

    @Test
    void theOwnerStillSeesTheirOwnContactDetails() throws Exception {
        signInAlice();

        mockMvc.perform(get("/users/me").with(jwt().jwt(j -> j.subject(ALICE)
                        .claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }
}

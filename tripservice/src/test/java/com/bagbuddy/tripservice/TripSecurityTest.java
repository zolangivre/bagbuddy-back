package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.UserInfo;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Les regles de securite sont testees sur la vraie surface HTTP : POST /trips/graphql traverse
 * la chaine de filtres, exactement comme un appel du front.
 *
 * Deux niveaux de refus coexistent en GraphQL et le test distingue les deux :
 *  - pas de jeton  -> 401 rendu par Spring Security, la requete n'atteint jamais le schema ;
 *  - jeton valide mais droit manquant -> 200 avec errors[].extensions.classification = FORBIDDEN.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TripSecurityTest {

    private static final String ALICE = "alice-sub";
    private static final String BOB = "bob-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        tripRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/trips/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    private Trip aliceTrip() {
        UserInfo info = new UserInfo();
        info.setSub(ALICE);
        info.setEmail("alice@example.com");
        // A l'inscription, l'email sert de nom d'utilisateur Keycloak.
        info.setUsername("alice@example.com");
        info.setPhone("+33600000000");
        info.setName("Alice");

        Trip trip = new Trip();
        trip.setUserId(ALICE);
        trip.setUserInfo(info);
        trip.setDepartureAirport("CDG");
        trip.setArrivalAirport("JFK");
        trip.setDepartureDate(LocalDateTime.now().plusDays(10));
        trip.setArrivalDate(LocalDateTime.now().plusDays(10).plusHours(8));
        trip.setTotalWeightAvailable(new BigDecimal("20"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setPricePerKg(new BigDecimal("12.50"));
        trip.setStripeAccountId("acct_alice");
        return tripRepository.save(trip);
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(graphql("{ trips { id } }")).andExpect(status().isUnauthorized());
        mockMvc.perform(graphql("mutation { deleteTrip(id: 1) }")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherMembersDoNotSeeContactDetailsOrPayoutAccount() throws Exception {
        aliceTrip();

        mockMvc.perform(graphql("{ trips { stripeAccountId userInfo { name username email phone } } }")
                        .with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips[0].userInfo.name").value("Alice"))
                .andExpect(jsonPath("$.data.trips[0].userInfo.username").doesNotExist())
                .andExpect(jsonPath("$.data.trips[0].userInfo.email").doesNotExist())
                .andExpect(jsonPath("$.data.trips[0].userInfo.phone").doesNotExist())
                .andExpect(jsonPath("$.data.trips[0].stripeAccountId").doesNotExist());
    }

    @Test
    void ownerStillSeesTheirOwnContactDetails() throws Exception {
        aliceTrip();

        mockMvc.perform(graphql("{ trips { stripeAccountId userInfo { username email } } }")
                        .with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips[0].userInfo.username").value("alice@example.com"))
                .andExpect(jsonPath("$.data.trips[0].userInfo.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.trips[0].stripeAccountId").value("acct_alice"));
    }

    @Test
    void thePayoutAccountIsReadableOnlyByItsOwner() throws Exception {
        aliceTrip();

        mockMvc.perform(graphql("query($u: String!) { payoutAccount(userId: $u) }",
                        Map.of("u", ALICE)).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        mockMvc.perform(graphql("query($u: String!) { payoutAccount(userId: $u) }",
                        Map.of("u", ALICE)).with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payoutAccount").value("acct_alice"));
    }

    @Test
    void aTripCannotBeEditedOrDeletedByAnotherMember() throws Exception {
        Trip trip = aliceTrip();

        mockMvc.perform(graphql("""
                        mutation($id: ID!, $input: TripInput!) {
                            updateTrip(id: $id, input: $input) { id }
                        }
                        """, Map.of("id", trip.getId(), "input", Map.of("departureAirport", "ORY")))
                        .with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        mockMvc.perform(graphql("mutation($id: ID!) { deleteTrip(id: $id) }",
                        Map.of("id", trip.getId())).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        assertThat(tripRepository.findById(trip.getId())).isPresent();
    }

    @Test
    void remainingWeightCannotBeSetByTheClient() throws Exception {
        // L'inventaire est decide par le serveur : le champ n'existe pas dans TripInput,
        // donc le schema refuse la requete au lieu de l'ignorer en silence.
        Map<String, Object> input = Map.of(
                "departureAirport", "CDG",
                "arrivalAirport", "JFK",
                "departureDate", LocalDateTime.now().plusDays(5).toString(),
                "arrivalDate", LocalDateTime.now().plusDays(6).toString(),
                "totalWeightAvailable", 10,
                "remainingWeight", 999,
                "pricePerKg", 5);

        mockMvc.perform(graphql("""
                        mutation($input: TripInput!) { createTrip(input: $input) { id } }
                        """, Map.of("input", input))
                        .with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));
    }

    @Test
    void updatingATripWithoutPayoutAccountKeepsTheStoredOne() throws Exception {
        Trip trip = aliceTrip();
        String update = """
                mutation($id: ID!, $input: TripInput!) { updateTrip(id: $id, input: $input) { id } }
                """;
        Map<String, Object> input = Map.of(
                "departureAirport", "ORY",
                "arrivalAirport", "JFK",
                "departureDate", LocalDateTime.now().plusDays(12).toString(),
                "arrivalDate", LocalDateTime.now().plusDays(13).toString(),
                "totalWeightAvailable", 20,
                "pricePerKg", 14);

        // Ce que le front web envoie : aucun stripeAccountId.
        mockMvc.perform(graphql(update, Map.of("id", trip.getId(), "input", input))
                        .with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());

        Trip stored = tripRepository.findById(trip.getId()).orElseThrow();
        assertThat(stored.getDepartureAirport()).isEqualTo("ORY");
        assertThat(stored.getStripeAccountId()).isEqualTo("acct_alice");

        // Un compte fourni explicitement remplace toujours l'ancien.
        Map<String, Object> withAccount = new HashMap<>(input);
        withAccount.put("stripeAccountId", "acct_alice_2");
        mockMvc.perform(graphql(update, Map.of("id", trip.getId(), "input", withAccount))
                        .with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getStripeAccountId())
                .isEqualTo("acct_alice_2");
    }

    @Test
    void ownershipComesFromTheTokenNotTheRequestBody() throws Exception {
        Map<String, Object> input = Map.of(
                "departureAirport", "CDG",
                "arrivalAirport", "JFK",
                "departureDate", LocalDateTime.now().plusDays(5).toString(),
                "arrivalDate", LocalDateTime.now().plusDays(6).toString(),
                "totalWeightAvailable", 10,
                "pricePerKg", 5,
                "profile", Map.of("phone", "+000"));

        mockMvc.perform(graphql("""
                        mutation($input: TripInput!) {
                            createTrip(input: $input) { userId userInfo { sub email } }
                        }
                        """, Map.of("input", input))
                        .with(jwt().jwt(j -> j.subject(BOB).claim("email", "bob@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTrip.userId").value(BOB));

        Trip stored = tripRepository.findAll().get(0);
        assertThat(stored.getUserId()).isEqualTo(BOB);
        assertThat(stored.getUserInfo().getSub()).isEqualTo(BOB);
        assertThat(stored.getUserInfo().getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void theSchemaItselfRefusesToTakeAnOwnerFromTheClient() throws Exception {
        // TripInput n'expose ni userId ni l'identite de l'instantane : l'usurpation est
        // refusee par le typage, avant meme d'atteindre un resolver.
        mockMvc.perform(graphql("""
                        mutation($input: TripInput!) { createTrip(input: $input) { id } }
                        """, Map.of("input", Map.of("userId", ALICE, "departureAirport", "CDG")))
                        .with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));

        assertThat(tripRepository.findAll()).isEmpty();
    }

    @Test
    void internalPricingEndpointIsClosedToUserTokens() throws Exception {
        Trip trip = aliceTrip();

        mockMvc.perform(get("/trips/internal/" + trip.getId()).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/trips/internal/" + trip.getId())
                        .with(jwt().jwt(j -> j.subject("stripeservice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userInfo.email").value("alice@example.com"));
    }

    @Test
    void capacityCanOnlyBeReservedByAService_andNeverBeyondWhatIsLeft() throws Exception {
        Trip trip = aliceTrip();
        var serviceRole = jwt().jwt(j -> j.subject("transactionservice"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));

        // Un acheteur ne peut pas decrementer le poids restant lui-meme.
        mockMvc.perform(post("/trips/internal/" + trip.getId() + "/reserve")
                        .with(jwt().jwt(j -> j.subject(BOB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":5}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/trips/internal/" + trip.getId() + "/reserve")
                        .with(serviceRole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingWeight").value(15));

        // On ne survend pas la capacite restante.
        mockMvc.perform(post("/trips/internal/" + trip.getId() + "/reserve")
                        .with(serviceRole)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weight\":99}"))
                .andExpect(status().isBadRequest());

        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getRemainingWeight())
                .isEqualByComparingTo("15");
    }
}

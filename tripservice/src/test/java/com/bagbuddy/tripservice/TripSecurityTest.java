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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    private Trip aliceTrip() {
        UserInfo info = new UserInfo();
        info.setSub(ALICE);
        info.setEmail("alice@example.com");
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
        mockMvc.perform(get("/trips")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/trips").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/trips/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherMembersDoNotSeeContactDetailsOrPayoutAccount() throws Exception {
        aliceTrip();

        mockMvc.perform(get("/trips").with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userInfo.name").value("Alice"))
                .andExpect(jsonPath("$[0].userInfo.email").doesNotExist())
                .andExpect(jsonPath("$[0].userInfo.phone").doesNotExist())
                .andExpect(jsonPath("$[0].stripeAccountId").doesNotExist());
    }

    @Test
    void ownerStillSeesTheirOwnContactDetails() throws Exception {
        aliceTrip();

        mockMvc.perform(get("/trips").with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userInfo.email").value("alice@example.com"))
                .andExpect(jsonPath("$[0].stripeAccountId").value("acct_alice"));
    }

    @Test
    void aTripCannotBeEditedOrDeletedByAnotherMember() throws Exception {
        Trip trip = aliceTrip();
        String body = objectMapper.writeValueAsString(trip);

        mockMvc.perform(put("/trips/" + trip.getId())
                        .with(jwt().jwt(j -> j.subject(BOB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/trips/" + trip.getId()).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isForbidden());

        assertThat(tripRepository.findById(trip.getId())).isPresent();
    }

    @Test
    void ownershipComesFromTheTokenNotTheRequestBody() throws Exception {
        String spoofed = """
                {
                  "userId": "alice-sub",
                  "userInfo": {"sub": "alice-sub", "email": "attacker@example.com", "phone": "+000"},
                  "departureAirport": "CDG",
                  "arrivalAirport": "JFK",
                  "departureDate": "%s",
                  "arrivalDate": "%s",
                  "totalWeightAvailable": 10,
                  "remainingWeight": 10,
                  "pricePerKg": 5
                }
                """.formatted(LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(6));

        mockMvc.perform(post("/trips")
                        .with(jwt().jwt(j -> j.subject(BOB).claim("email", "bob@example.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(BOB));

        Trip stored = tripRepository.findAll().get(0);
        assertThat(stored.getUserId()).isEqualTo(BOB);
        assertThat(stored.getUserInfo().getSub()).isEqualTo(BOB);
        assertThat(stored.getUserInfo().getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void internalPricingEndpointIsClosedToUserTokens() throws Exception {
        Trip trip = aliceTrip();

        mockMvc.perform(get("/trips/internal/" + trip.getId()).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/trips/internal/" + trip.getId())
                        .with(jwt().jwt(j -> j.subject("stripeservice"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userInfo.email").value("alice@example.com"));
    }

    @Test
    void capacityCanOnlyBeReservedByAService_andNeverBeyondWhatIsLeft() throws Exception {
        Trip trip = aliceTrip();
        var serviceRole = jwt().jwt(j -> j.subject("transactionservice"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SERVICE"));

        // Un acheteur ne peut plus decrementer le poids restant lui-meme.
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

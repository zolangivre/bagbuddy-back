package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.UserInfo;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.repository.TripReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lecture des favoris d'un membre : l'ordre demande, sans les annonces disparues, bornee. */
@SpringBootTest
@AutoConfigureMockMvc
class TripsByIdsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripReservationRepository reservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        reservationRepository.deleteAll();
        tripRepository.deleteAll();
    }

    private Trip trip(String from, String to) {
        UserInfo info = new UserInfo();
        info.setSub("alice-sub");
        info.setName("Alice");
        info.setEmail("alice@example.com");
        Trip trip = new Trip();
        trip.setUserId("alice-sub");
        trip.setUserInfo(info);
        trip.setDepartureAirport(from);
        trip.setArrivalAirport(to);
        trip.setDepartureDate(LocalDateTime.now().plusDays(10));
        trip.setArrivalDate(LocalDateTime.now().plusDays(10).plusHours(8));
        trip.setTotalWeightAvailable(new BigDecimal("20"));
        trip.setRemainingWeight(new BigDecimal("20"));
        trip.setPricePerKg(new BigDecimal("10"));
        return tripRepository.save(trip);
    }

    private MockHttpServletRequestBuilder byIds(List<?> ids) throws Exception {
        return post("/trips/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "query", "query($ids: [ID!]!) { tripsByIds(ids: $ids) { id departureAirport userInfo { email } } }",
                        "variables", Map.of("ids", ids))));
    }

    @Test
    void returnsTheRequestedOrderAndSkipsUnknownIds() throws Exception {
        Trip first = trip("CDG", "JFK");
        Trip second = trip("DSS", "MRS");

        mockMvc.perform(byIds(List.of(second.getId(), 999_999L, first.getId(), second.getId()))
                        .with(jwt().jwt(j -> j.subject("bob-sub"))))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.tripsByIds.length()").value(2))
                .andExpect(jsonPath("$.data.tripsByIds[0].departureAirport").value("DSS"))
                .andExpect(jsonPath("$.data.tripsByIds[1].departureAirport").value("CDG"))
                // Memes regles de visibilite que les autres lectures : pas d'email pour un tiers.
                .andExpect(jsonPath("$.data.tripsByIds[0].userInfo.email").doesNotExist());
    }

    @Test
    void requiresATokenAndIsCapped() throws Exception {
        mockMvc.perform(byIds(List.of(1))).andExpect(status().isUnauthorized());

        List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
        mockMvc.perform(byIds(tooMany).with(jwt().jwt(j -> j.subject("bob-sub"))))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
    }
}

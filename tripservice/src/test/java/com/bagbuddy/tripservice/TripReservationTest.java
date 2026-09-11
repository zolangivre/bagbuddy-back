package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.repository.TripReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capacite d'une annonce est rattachee aux transactions qui l'ont prise : c'est ce qui permet
 * de rejouer une reservation sans double decompte et de rendre le poids a l'annulation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TripReservationTest {

    private static final String OWNER = "owner-sub";

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

    private Trip listing(String total, LocalDateTime departure) {
        Trip trip = new Trip();
        trip.setUserId(OWNER);
        trip.setDepartureAirport("CDG");
        trip.setArrivalAirport("JFK");
        trip.setDepartureDate(departure);
        trip.setArrivalDate(departure.plusHours(8));
        trip.setTotalWeightAvailable(new BigDecimal(total));
        trip.setRemainingWeight(new BigDecimal(total));
        trip.setPricePerKg(new BigDecimal("10"));
        return tripRepository.save(trip);
    }

    private Trip listing(String total) {
        return listing(total, LocalDateTime.now().plusDays(10));
    }

    private ResultActions internal(Long tripId, String action, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/trips/internal/" + tripId + "/" + action)
                .with(jwt().jwt(j -> j.subject("transactionservice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private BigDecimal remaining(Long tripId) {
        return tripRepository.findById(tripId).orElseThrow().getRemainingWeight();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/trips/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    @Test
    void replayingTheSameReservationDoesNotTakeTheWeightTwice() throws Exception {
        Trip trip = listing("20");

        // Le vendeur double-clique sur "accepter" : deux appels pour la meme transaction.
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(15));
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(15));

        assertThat(remaining(trip.getId())).isEqualByComparingTo("15");
        assertThat(reservationRepository.findAll()).hasSize(1);
    }

    @Test
    void aReplayCannotQuietlyChangeTheReservedWeight() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7)).andExpect(status().isOk());

        internal(trip.getId(), "reserve", Map.of("weight", 8, "transactionId", 7))
                .andExpect(status().isBadRequest());
        assertThat(remaining(trip.getId())).isEqualByComparingTo("15");
    }

    @Test
    void aReservationWithoutTransactionIsRefused() throws Exception {
        Trip trip = listing("20");

        internal(trip.getId(), "reserve", Map.of("weight", 5)).andExpect(status().isBadRequest());
        assertThat(remaining(trip.getId())).isEqualByComparingTo("20");
    }

    @Test
    void releasingGivesTheWeightBackExactlyOnce() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7)).andExpect(status().isOk());

        internal(trip.getId(), "release", Map.of("transactionId", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(20));
        internal(trip.getId(), "release", Map.of("transactionId", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(20));
        // Une transaction qui n'a jamais rien reserve ne cree pas de capacite.
        internal(trip.getId(), "release", Map.of("transactionId", 999))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(20));

        assertThat(remaining(trip.getId())).isEqualByComparingTo("20");
    }

    @Test
    void aReleasedReservationCanBeTakenAgain() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7)).andExpect(status().isOk());
        internal(trip.getId(), "release", Map.of("transactionId", 7)).andExpect(status().isOk());

        // Demande re-tarifee puis acceptee a nouveau, avec un autre poids.
        internal(trip.getId(), "reserve", Map.of("weight", 3, "transactionId", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingWeight").value(17));
        assertThat(reservationRepository.findAll()).hasSize(1);
    }

    @Test
    void releasingNeverRaisesTheListingAboveItsTotal() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7)).andExpect(status().isOk());

        Trip stored = tripRepository.findById(trip.getId()).orElseThrow();
        stored.setRemainingWeight(new BigDecimal("18"));
        tripRepository.save(stored);

        internal(trip.getId(), "release", Map.of("transactionId", 7)).andExpect(status().isOk());
        assertThat(remaining(trip.getId())).isEqualByComparingTo("20");
    }

    @Test
    void aTripWithAcceptedBookingsCannotBeDeletedUntilTheyAreCancelled() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 5, "transactionId", 7)).andExpect(status().isOk());
        var owner = jwt().jwt(j -> j.subject(OWNER));

        mockMvc.perform(graphql("mutation($id: ID!) { deleteTrip(id: $id) }", Map.of("id", trip.getId()))
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));
        assertThat(tripRepository.findById(trip.getId())).isPresent();

        internal(trip.getId(), "release", Map.of("transactionId", 7)).andExpect(status().isOk());

        mockMvc.perform(graphql("mutation($id: ID!) { deleteTrip(id: $id) }", Map.of("id", trip.getId()))
                        .with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteTrip").value(true));
        assertThat(tripRepository.findById(trip.getId())).isEmpty();
        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void theTotalCannotDropBelowWhatIsAlreadyBooked() throws Exception {
        Trip trip = listing("20");
        internal(trip.getId(), "reserve", Map.of("weight", 12, "transactionId", 7)).andExpect(status().isOk());

        Map<String, Object> input = Map.of(
                "departureAirport", "CDG",
                "arrivalAirport", "JFK",
                "departureDate", LocalDateTime.now().plusDays(10).toString(),
                "arrivalDate", LocalDateTime.now().plusDays(11).toString(),
                "totalWeightAvailable", 10,
                "pricePerKg", 10);

        mockMvc.perform(graphql("""
                        mutation($id: ID!, $input: TripInput!) { updateTrip(id: $id, input: $input) { id } }
                        """, Map.of("id", trip.getId(), "input", input))
                        .with(jwt().jwt(j -> j.subject(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        Trip stored = tripRepository.findById(trip.getId()).orElseThrow();
        assertThat(stored.getTotalWeightAvailable()).isEqualByComparingTo("20");
        assertThat(stored.getRemainingWeight()).isEqualByComparingTo("8");
    }

    @Test
    void anOffsetThatIsNotAMultipleOfTheLimitSkipsNothing() throws Exception {
        for (int i = 0; i < 5; i++) {
            listing("10");
        }
        var member = jwt().jwt(j -> j.subject("member-sub"));
        String page = "query($l: Int, $o: Int) { trips(limit: $l, offset: $o) { id } }";

        String all = mockMvc.perform(graphql(page, Map.of("l", 5, "o", 0)).with(member))
                .andReturn().getResponse().getContentAsString();
        var ids = objectMapper.readTree(all).at("/data/trips");

        // offset=3 limit=2 : les 4e et 5e annonces, et non la page 1 (3e et 4e).
        mockMvc.perform(graphql(page, Map.of("l", 2, "o", 3)).with(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips.length()").value(2))
                .andExpect(jsonPath("$.data.trips[0].id").value(ids.get(3).get("id").asText()))
                .andExpect(jsonPath("$.data.trips[1].id").value(ids.get(4).get("id").asText()));
    }
}

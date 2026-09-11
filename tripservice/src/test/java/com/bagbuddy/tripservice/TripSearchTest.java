package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.UserInfo;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.repository.TripReservationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class TripSearchTest {

    private static final String QUERY = """
            query($f: TripSearchInput, $l: Int, $o: Int) {
                searchTrips(filter: $f, limit: $l, offset: $o) {
                    totalCount totalRemainingWeight averagePricePerKg
                    items { id departureAirport arrivalAirport pricePerKg remainingWeight
                            userInfo { name email phone } }
                }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripReservationRepository reservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** Toutes les dates sont posees a partir de ce jour, a midi, loin de tout minuit. */
    private final LocalDate inTenDays = LocalDate.now().plusDays(10);

    @BeforeEach
    void reset() {
        reservationRepository.deleteAll();
        tripRepository.deleteAll();
    }

    private Trip trip(String from, String to, LocalDateTime departure, String price, String remaining) {
        UserInfo info = new UserInfo();
        info.setName("Owner");
        info.setEmail("owner@example.com");
        info.setPhone("+33600000000");

        Trip trip = new Trip();
        trip.setUserId("owner-sub");
        trip.setUserInfo(info);
        trip.setDepartureAirport(from);
        trip.setArrivalAirport(to);
        trip.setDepartureDate(departure);
        trip.setArrivalDate(departure.plusHours(8));
        trip.setPricePerKg(new BigDecimal(price));
        trip.setTotalWeightAvailable(new BigDecimal("30"));
        trip.setRemainingWeight(new BigDecimal(remaining));
        return tripRepository.save(trip);
    }

    private LocalDateTime noon(int daysFromTarget) {
        return inTenDays.plusDays(daysFromTarget).atTime(12, 0);
    }

    private JsonNode search(Map<String, Object> filter, Integer limit, Integer offset) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("f", filter);
        variables.put("l", limit);
        variables.put("o", offset);
        String body = mockMvc.perform(post("/trips/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("query", QUERY, "variables", variables)))
                        .with(jwt().jwt(j -> j.subject("searcher-sub"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<Long> ids(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.at("/data/searchTrips/items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    @Test
    void onlyBookableTripsAreSearchedAndTheRouteIsCaseInsensitive() throws Exception {
        Trip match = trip("CDG", "JFK", noon(0), "10", "5");
        trip("CDG", "LAX", noon(0), "10", "5");                          // autre destination
        trip("CDG", "JFK", LocalDateTime.now().minusHours(2), "10", "5"); // deja parti
        trip("CDG", "JFK", noon(0), "10", "0");                          // complet

        JsonNode result = search(Map.of("departureAirport", " cdg ", "arrivalAirport", "jfk"), null, null);

        assertThat(ids(result)).containsExactly(match.getId());
        assertThat(result.at("/data/searchTrips/totalCount").asInt()).isEqualTo(1);
    }

    @Test
    void theDateWindowFollowsCalendarDaysWithTheRequestedFlexibility() throws Exception {
        Trip sameDay = trip("CDG", "JFK", inTenDays.atTime(23, 59), "10", "5");
        Trip dayBefore = trip("CDG", "JFK", inTenDays.minusDays(1).atTime(0, 1), "10", "5");
        Trip twoDaysAfter = trip("CDG", "JFK", noon(2), "10", "5");

        assertThat(ids(search(Map.of("date", inTenDays.toString()), null, null)))
                .containsExactly(sameDay.getId());
        assertThat(ids(search(Map.of("date", inTenDays.toString(), "flexDays", 1), null, null)))
                .containsExactlyInAnyOrder(sameDay.getId(), dayBefore.getId());
        assertThat(ids(search(Map.of("date", inTenDays.toString(), "flexDays", 3), null, null)))
                .containsExactlyInAnyOrder(sameDay.getId(), dayBefore.getId(), twoDaysAfter.getId());
    }

    @Test
    void priceAndWeightBoundsAreInclusive() throws Exception {
        Trip cheap = trip("CDG", "JFK", noon(0), "5", "2");
        Trip middle = trip("CDG", "JFK", noon(0), "10", "10");
        trip("CDG", "JFK", noon(0), "20", "25");

        JsonNode result = search(Map.of(
                "minPricePerKg", 5, "maxPricePerKg", 10,
                "minWeight", 2, "maxWeight", 10), null, null);

        assertThat(ids(result)).containsExactlyInAnyOrder(cheap.getId(), middle.getId());
    }

    @Test
    void sortingHappensInTheDatabaseAndStaysStable() throws Exception {
        Trip expensive = trip("CDG", "JFK", noon(3), "20", "5");
        Trip cheap = trip("CDG", "JFK", noon(1), "5", "25");
        Trip middle = trip("CDG", "JFK", noon(2), "10", "10");

        assertThat(ids(search(Map.of("sort", "PRICE_LOW"), null, null)))
                .containsExactly(cheap.getId(), middle.getId(), expensive.getId());
        assertThat(ids(search(Map.of("sort", "PRICE_HIGH"), null, null)))
                .containsExactly(expensive.getId(), middle.getId(), cheap.getId());
        assertThat(ids(search(Map.of("sort", "EARLIEST_DEPARTURE"), null, null)))
                .containsExactly(cheap.getId(), middle.getId(), expensive.getId());
        assertThat(ids(search(Map.of("sort", "WEIGHT_HIGH"), null, null)))
                .containsExactly(cheap.getId(), middle.getId(), expensive.getId());
    }

    @Test
    void aggregatesCoverTheWholeFilterNotJustThePage() throws Exception {
        trip("CDG", "JFK", noon(1), "5", "2");
        trip("CDG", "JFK", noon(2), "10", "10");
        trip("CDG", "JFK", noon(3), "15", "3");

        JsonNode result = search(Map.of("sort", "EARLIEST_DEPARTURE"), 1, 1);

        assertThat(result.at("/data/searchTrips/items")).hasSize(1);
        assertThat(result.at("/data/searchTrips/totalCount").asInt()).isEqualTo(3);
        assertThat(result.at("/data/searchTrips/totalRemainingWeight").decimalValue()).isEqualByComparingTo("15");
        assertThat(result.at("/data/searchTrips/averagePricePerKg").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    void anEmptySearchHasNoAverage() throws Exception {
        JsonNode result = search(Map.of("departureAirport", "NRT"), null, null);

        assertThat(result.at("/data/searchTrips/totalCount").asInt()).isZero();
        assertThat(result.at("/data/searchTrips/averagePricePerKg").isNull()).isTrue();
    }

    @Test
    void contactDetailsStayHiddenFromOtherMembers() throws Exception {
        trip("CDG", "JFK", noon(0), "10", "5");

        JsonNode item = search(null, null, null).at("/data/searchTrips/items/0/userInfo");

        assertThat(item.get("name").asText()).isEqualTo("Owner");
        assertThat(item.get("email").isNull()).isTrue();
        assertThat(item.get("phone").isNull()).isTrue();
    }

    @Test
    void malformedFiltersAreRefused() throws Exception {
        assertThat(search(Map.of("date", "11/09/2026"), null, null)
                .at("/errors/0/extensions/classification").asText()).isEqualTo("BAD_REQUEST");
        assertThat(search(Map.of("date", inTenDays.toString(), "flexDays", 90), null, null)
                .at("/errors/0/extensions/classification").asText()).isEqualTo("BAD_REQUEST");
        assertThat(search(Map.of("minWeight", -1), null, null)
                .at("/errors/0/extensions/classification").asText()).isEqualTo("BAD_REQUEST");
    }
}

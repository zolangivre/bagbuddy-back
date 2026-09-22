package com.bagbuddy.tripservice;

import com.bagbuddy.tripservice.repository.TripAlertRepository;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.repository.TripReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alertes de trajet : qui recoit un email quand une annonce est publiee, et qui n'en recoit pas. */
@SpringBootTest
@AutoConfigureMockMvc
class TripAlertTest {

    private static final String CREATE_ALERT =
            "mutation($i: TripAlertInput!) { createTripAlert(input: $i) { id departureAirport date flexDays } }";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TripAlertRepository alerts;

    @Autowired
    private TripRepository trips;

    @Autowired
    private TripReservationRepository reservations;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void reset() {
        alerts.deleteAll();
        reservations.deleteAll();
        trips.deleteAll();
    }

    private static RequestPostProcessor member(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("email", sub + "@example.com").claim("name", sub));
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/trips/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    private void alert(String sub, Map<String, Object> input) throws Exception {
        mockMvc.perform(graphql(CREATE_ALERT, Map.of("i", input)).with(member(sub)))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    private void publish(String owner, LocalDateTime departure, String price, String weight) throws Exception {
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        Map<String, Object> input = new HashMap<>();
        input.put("departureAirport", "cdg");
        input.put("arrivalAirport", "DSS");
        input.put("departureDate", departure.format(iso));
        input.put("arrivalDate", departure.plusHours(6).format(iso));
        input.put("totalWeightAvailable", weight);
        input.put("pricePerKg", price);
        mockMvc.perform(graphql("mutation($i: TripInput!) { createTrip(input: $i) { id } }", Map.of("i", input))
                        .with(member(owner)))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void alertsRequireATokenAndAValidRoute() throws Exception {
        mockMvc.perform(graphql("{ myTripAlerts { id } }", Map.of())).andExpect(status().isUnauthorized());

        mockMvc.perform(graphql(CREATE_ALERT, Map.of("i", Map.of("departureAirport", "CDG", "arrivalAirport", "cdg")))
                        .with(member("alice")))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("alert_invalid_route"));
        mockMvc.perform(graphql(CREATE_ALERT, Map.of("i", Map.of("departureAirport", "PARIS", "arrivalAirport", "DSS")))
                        .with(member("alice")))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("alert_invalid_route"));
        assertThat(alerts.findAll()).isEmpty();
    }

    @Test
    void alertsAreCappedAndPrivateToTheirOwner() throws Exception {
        for (int i = 0; i < 10; i++) {
            alert("alice", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS"));
        }
        mockMvc.perform(graphql(CREATE_ALERT, Map.of("i", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS")))
                        .with(member("alice")))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("too_many_alerts"));

        mockMvc.perform(graphql("{ myTripAlerts { id } }", Map.of()).with(member("bob")))
                .andExpect(jsonPath("$.data.myTripAlerts.length()").value(0));

        Long aliceAlert = alerts.findBySubOrderByCreatedAtDesc("alice").get(0).getId();
        mockMvc.perform(graphql("mutation($id: ID!) { deleteTripAlert(id: $id) }", Map.of("id", aliceAlert))
                        .with(member("bob")))
                .andExpect(jsonPath("$.data.deleteTripAlert").value(false));
        mockMvc.perform(graphql("mutation($id: ID!) { deleteTripAlert(id: $id) }", Map.of("id", aliceAlert))
                        .with(member("alice")))
                .andExpect(jsonPath("$.data.deleteTripAlert").value(true));
        assertThat(alerts.countBySub("alice")).isEqualTo(9);
    }

    @Test
    void aPublishedListingEmailsOnlyTheAlertsItMatches() throws Exception {
        LocalDateTime departure = LocalDate.now().plusDays(20).atTime(23, 30);
        String day = departure.toLocalDate().toString();

        alert("route-only", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS", "language", "fr"));
        alert("date-window", Map.of("departureAirport", "cdg", "arrivalAirport", "dss",
                "date", departure.toLocalDate().minusDays(2).toString(), "flexDays", 3));
        alert("date-too-far", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS",
                "date", departure.toLocalDate().minusDays(5).toString(), "flexDays", 3));
        alert("too-cheap", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS", "maxPricePerKg", "5"));
        alert("needs-more-room", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS", "minWeight", "30"));
        alert("other-route", Map.of("departureAirport", "DSS", "arrivalAirport", "CDG", "date", day));
        // L'auteur de l'annonce avait lui aussi une alerte sur ce trajet : il ne s'ecrit pas a lui-meme.
        alert("traveller", Map.of("departureAirport", "CDG", "arrivalAirport", "DSS"));

        publish("traveller", departure, "9.50", "20");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(2000).times(2)).send(sent.capture());
        verify(mailSender, after(300).times(2)).send(any(SimpleMailMessage.class));

        List<String> recipients = sent.getAllValues().stream().map(message -> message.getTo()[0]).toList();
        assertThat(recipients).containsExactlyInAnyOrder("route-only@example.com", "date-window@example.com");
        SimpleMailMessage french = sent.getAllValues().stream()
                .filter(message -> message.getTo()[0].startsWith("route-only")).findFirst().orElseThrow();
        assertThat(french.getSubject()).isEqualTo("Nouveau trajet CDG → DSS · BagBuddy");
        assertThat(french.getText()).contains("/transaction-detail?listingId=").contains("20 kg disponibles");
    }
}

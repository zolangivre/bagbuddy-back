package com.bagbuddy.userservice;

import com.bagbuddy.userservice.client.KeycloakAdminClient;
import com.bagbuddy.userservice.repository.FavoriteListingRepository;
import com.bagbuddy.userservice.repository.MemberReportRepository;
import com.bagbuddy.userservice.service.ModerationMailer;
import com.bagbuddy.userservice.service.ReportService.ReportFiled;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Favoris et signalements : sur le schema ouvert pour l'inscription, chaque operation exige un
 * jeton et n'agit que pour l'appelant.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FavoritesAndReportsTest {

    private static final String ALICE = "alice-sub";
    private static final String BOB = "bob-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FavoriteListingRepository favorites;

    @Autowired
    private MemberReportRepository reports;

    @MockitoBean
    private KeycloakAdminClient keycloak;

    @MockitoBean
    private ModerationMailer moderationMailer;

    @BeforeEach
    void reset() {
        favorites.deleteAll();
        reports.deleteAll();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables) throws Exception {
        return post("/users/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query, "variables", variables)));
    }

    @Test
    void everyOperationRequiresAToken() throws Exception {
        Map<String, Map<String, Object>> operations = Map.of(
                "{ favoriteListingIds }", Map.of(),
                "mutation($l: ID!) { addFavoriteListing(listingId: $l) }", Map.of("l", 1),
                "mutation($l: ID!) { removeFavoriteListing(listingId: $l) }", Map.of("l", 1),
                "mutation($i: ReportMemberInput!) { reportMember(input: $i) }",
                Map.of("i", Map.of("reportedSub", BOB, "reason", "FRAUD")));

        for (var operation : operations.entrySet()) {
            mockMvc.perform(graphql(operation.getKey(), operation.getValue()))
                    .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"));
        }
        assertThat(favorites.findAll()).isEmpty();
        assertThat(reports.findAll()).isEmpty();
    }

    @Test
    void favoritesAreIdempotentAndPrivateToTheirOwner() throws Exception {
        var alice = jwt().jwt(j -> j.subject(ALICE));
        String add = "mutation($l: ID!) { addFavoriteListing(listingId: $l) }";

        mockMvc.perform(graphql(add, Map.of("l", 15)).with(alice))
                .andExpect(jsonPath("$.data.addFavoriteListing").value(true));
        mockMvc.perform(graphql(add, Map.of("l", 15)).with(alice))
                .andExpect(jsonPath("$.data.addFavoriteListing").value(true));
        mockMvc.perform(graphql(add, Map.of("l", 4)).with(alice))
                .andExpect(jsonPath("$.errors").doesNotExist());

        mockMvc.perform(graphql("{ favoriteListingIds }", Map.of()).with(alice))
                .andExpect(jsonPath("$.data.favoriteListingIds.length()").value(2));
        // Bob ne voit pas les favoris d'Alice : la query n'a pas d'argument de membre.
        mockMvc.perform(graphql("{ favoriteListingIds }", Map.of()).with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(jsonPath("$.data.favoriteListingIds.length()").value(0));

        mockMvc.perform(graphql("mutation($l: ID!) { removeFavoriteListing(listingId: $l) }", Map.of("l", 15))
                        .with(alice))
                .andExpect(jsonPath("$.data.removeFavoriteListing").value(true));
        mockMvc.perform(graphql("mutation($l: ID!) { removeFavoriteListing(listingId: $l) }", Map.of("l", 15))
                        .with(alice))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(favorites.findBySubOrderByCreatedAtDesc(ALICE)).singleElement()
                .satisfies(favorite -> assertThat(favorite.getListingId()).isEqualTo(4L));
    }

    @Test
    void aReportIsStoredForTheCallerAndSentToTheModerators() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("reportedSub", BOB);
        input.put("transactionId", 22);
        input.put("reason", "NO_SHOW");
        input.put("details", "  Absent au comptoir d'enregistrement.  ");

        mockMvc.perform(graphql("mutation($i: ReportMemberInput!) { reportMember(input: $i) }", Map.of("i", input))
                        .with(jwt().jwt(j -> j.subject(ALICE).claim("email", "alice@example.com"))))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.reportMember").value(true));

        assertThat(reports.findAll()).singleElement().satisfies(report -> {
            assertThat(report.getReporterSub()).isEqualTo(ALICE);
            assertThat(report.getReportedSub()).isEqualTo(BOB);
            assertThat(report.getTransactionId()).isEqualTo(22L);
            assertThat(report.getDetails()).isEqualTo("Absent au comptoir d'enregistrement.");
        });
        ArgumentCaptor<ReportFiled> filed = ArgumentCaptor.forClass(ReportFiled.class);
        verify(moderationMailer, timeout(2000)).onReportFiled(filed.capture());
        assertThat(filed.getValue().reporterEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void nobodyCanReportThemselves() throws Exception {
        mockMvc.perform(graphql("mutation($i: ReportMemberInput!) { reportMember(input: $i) }",
                        Map.of("i", Map.of("reportedSub", ALICE, "reason", "OTHER")))
                        .with(jwt().jwt(j -> j.subject(ALICE))))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("cannot_report_self"));
        assertThat(reports.findAll()).isEmpty();
    }

    @Test
    void reportsAreCappedPerReporterPerDay() throws Exception {
        var alice = jwt().jwt(j -> j.subject(ALICE));
        String mutation = "mutation($i: ReportMemberInput!) { reportMember(input: $i) }";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(graphql(mutation, Map.of("i", Map.of("reportedSub", "member-" + i, "reason", "FRAUD")))
                            .with(alice))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }

        mockMvc.perform(graphql(mutation, Map.of("i", Map.of("reportedSub", BOB, "reason", "FRAUD"))).with(alice))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("too_many_reports"));
        // Le plafond est par auteur : Bob, lui, peut toujours signaler.
        mockMvc.perform(graphql(mutation, Map.of("i", Map.of("reportedSub", ALICE, "reason", "FRAUD")))
                        .with(jwt().jwt(j -> j.subject(BOB))))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(moderationMailer, timeout(2000).times(11)).onReportFiled(any());
    }
}

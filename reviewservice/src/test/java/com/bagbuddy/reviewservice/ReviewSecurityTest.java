package com.bagbuddy.reviewservice;

import com.bagbuddy.reviewservice.client.TransactionClient;
import com.bagbuddy.reviewservice.client.TransactionSnapshot;
import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * La participation a la transaction n'est pas jugee ici : reviewservice relit la transaction
 * avec le jeton de l'appelant, et transactionservice refuse de la servir a un tiers. Le client
 * est donc mocke pour rejouer les deux reponses possibles.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewSecurityTest {

    private static final String BUYER = "buyer-sub";
    private static final String SELLER = "seller-sub";
    private static final String STRANGER = "stranger-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionClient transactionClient;

    @BeforeEach
    void reset() {
        reviewRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder graphql(String query, Map<String, Object> variables)
            throws Exception {
        return post("/reviews/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("query", query, "variables", variables)));
    }

    private MockHttpServletRequestBuilder graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    /** Transaction ou l'acheteur et le vendeur se font face, telle que la verrait l'appelant. */
    private TransactionSnapshot deal() {
        TransactionSnapshot.Party buyer = new TransactionSnapshot.Party();
        buyer.setSub(BUYER);
        buyer.setName("Buyer");

        TransactionSnapshot.Party seller = new TransactionSnapshot.Party();
        seller.setSub(SELLER);
        seller.setName("Seller");

        TransactionSnapshot.Listing listing = new TransactionSnapshot.Listing();
        listing.setSellerUserInfo(seller);

        TransactionSnapshot snapshot = new TransactionSnapshot();
        snapshot.setId(1L);
        snapshot.setBuyerId(BUYER);
        snapshot.setSellerId(SELLER);
        snapshot.setBuyerInfo(buyer);
        snapshot.setListingInfo(listing);
        return snapshot;
    }

    private Review buyerReviewOfSeller() {
        Review review = new Review();
        review.setTransactionId(1L);
        review.setReviewerId(BUYER);
        review.setRevieweeId(SELLER);
        review.setRating(5);
        review.setComment("Parfait");
        return reviewRepository.save(review);
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(graphql("{ reviews { id } }")).andExpect(status().isUnauthorized());
        mockMvc.perform(graphql("mutation { deleteReview(id: 1) }")).andExpect(status().isUnauthorized());
    }

    @Test
    void aReviewCannotBeWrittenAboutADealTheCallerWasNotPartOf() throws Exception {
        when(transactionClient.fetchAsCaller(any(), anyString()))
                .thenThrow(new AccessDeniedException("Caller is not a party to transaction 1"));

        mockMvc.perform(graphql("""
                        mutation($i: CreateReviewInput!) { createReview(input: $i) { id } }
                        """, Map.of("i", Map.of("transactionId", 1, "rating", 1, "comment", "nul")))
                        .with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        assertThat(reviewRepository.findAll()).isEmpty();
    }

    @Test
    void theAuthorComesFromTheTokenAndTheSubjectFromTheTransaction() throws Exception {
        when(transactionClient.fetchAsCaller(any(), anyString())).thenReturn(deal());

        mockMvc.perform(graphql("""
                        mutation($i: CreateReviewInput!) {
                            createReview(input: $i) { reviewerId revieweeId revieweeName }
                        }
                        """, Map.of("i", Map.of("transactionId", 1, "rating", 5, "comment", "Top")))
                        .with(jwt().jwt(j -> j.subject(BUYER).claim("name", "Buyer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createReview.reviewerId").value(BUYER))
                .andExpect(jsonPath("$.data.createReview.revieweeId").value(SELLER))
                .andExpect(jsonPath("$.data.createReview.revieweeName").value("Seller"));
    }

    @Test
    void theSchemaRefusesToTakeAnAuthorOrASubjectFromTheClient() throws Exception {
        Map<String, Object> forged = new HashMap<>();
        forged.put("transactionId", 1);
        forged.put("rating", 5);
        forged.put("reviewerId", STRANGER);
        forged.put("revieweeId", SELLER);

        mockMvc.perform(graphql("""
                        mutation($i: CreateReviewInput!) { createReview(input: $i) { id } }
                        """, Map.of("i", forged)).with(jwt().jwt(j -> j.subject(STRANGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("ValidationError"));

        assertThat(reviewRepository.findAll()).isEmpty();
    }

    @Test
    void onlyTheAuthorMayEditOrDeleteTheirReview() throws Exception {
        Review review = buyerReviewOfSeller();
        var seller = jwt().jwt(j -> j.subject(SELLER));

        mockMvc.perform(graphql("""
                        mutation($id: ID!, $i: UpdateReviewInput!) {
                            updateReview(id: $id, input: $i) { id }
                        }
                        """, Map.of("id", review.getId(), "i", Map.of("rating", 1, "comment", "edite")))
                        .with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        mockMvc.perform(graphql("mutation($id: ID!) { deleteReview(id: $id) }",
                        Map.of("id", review.getId())).with(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));

        assertThat(reviewRepository.findById(review.getId())).isPresent();
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getRating()).isEqualTo(5);
    }

    @Test
    void aRatingOutsideOneToFiveIsRefused() throws Exception {
        when(transactionClient.fetchAsCaller(any(), anyString())).thenReturn(deal());

        mockMvc.perform(graphql("""
                        mutation($i: CreateReviewInput!) { createReview(input: $i) { id } }
                        """, Map.of("i", Map.of("transactionId", 1, "rating", 9)))
                        .with(jwt().jwt(j -> j.subject(BUYER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"));

        assertThat(reviewRepository.findAll()).isEmpty();
    }
}

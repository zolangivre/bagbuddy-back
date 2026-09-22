package com.bagbuddy.reviewservice;

import com.bagbuddy.reviewservice.client.TransactionClient;
import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewIntegrityTest {

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

    private Review review(String reviewer, long transactionId) {
        Review review = new Review();
        review.setReviewerId(reviewer);
        review.setRevieweeId("someone-else");
        review.setTransactionId(transactionId);
        review.setRating(4);
        return review;
    }

    /**
     * Le controle existsBy... de ReviewService ne voit pas un envoi concurrent : c'est la base
     * qui doit refuser le second avis.
     */
    @Test
    void theDatabaseRefusesASecondReviewOfTheSameTransactionByTheSameAuthor() {
        reviewRepository.saveAndFlush(review("buyer-sub", 1L));

        assertThatThrownBy(() -> reviewRepository.saveAndFlush(review("buyer-sub", 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // L'autre partie garde le droit de noter la meme transaction.
        reviewRepository.saveAndFlush(review("seller-sub", 1L));
        assertThat(reviewRepository.count()).isEqualTo(2);
    }

    @Test
    void anOffsetThatIsNotAMultipleOfTheLimitSkipsNothing() throws Exception {
        for (long i = 1; i <= 5; i++) {
            reviewRepository.saveAndFlush(review("author-" + i, i));
        }
        String page = "query($l: Int, $o: Int) { reviews(limit: $l, offset: $o) { id } }";
        var member = jwt().jwt(j -> j.subject("member-sub"));

        String all = mockMvc.perform(post("/reviews/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", page, "variables", Map.of("l", 5, "o", 0))))
                        .with(member))
                .andReturn().getResponse().getContentAsString();
        var ids = objectMapper.readTree(all).at("/data/reviews");

        mockMvc.perform(post("/reviews/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("query", page, "variables", Map.of("l", 2, "o", 3))))
                        .with(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviews.length()").value(2))
                .andExpect(jsonPath("$.data.reviews[0].id").value(ids.get(3).get("id").asText()))
                .andExpect(jsonPath("$.data.reviews[1].id").value(ids.get(4).get("id").asText()));
    }
}

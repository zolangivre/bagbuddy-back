package com.bagbuddy.reviewservice.service;

import com.bagbuddy.reviewservice.client.TransactionClient;
import com.bagbuddy.reviewservice.client.TransactionSnapshot;
import com.bagbuddy.reviewservice.model.Review;
import com.bagbuddy.reviewservice.repository.ReviewRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import com.bagbuddy.reviewservice.repository.OffsetPageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final TransactionClient transactionClient;

    public ReviewService(ReviewRepository reviewRepository, TransactionClient transactionClient) {
        this.reviewRepository = reviewRepository;
        this.transactionClient = transactionClient;
    }

    /** Plafond dur : une lecture non filtree ne doit jamais pouvoir ramener toute la table. */
    public static final int MAX_PAGE = 200;

    /** limit/offset optionnels ; sans eux la page vaut MAX_PAGE. L'offset est pris tel quel. */
    private static OffsetPageRequest page(Integer limit, Integer offset, Sort sort) {
        int size = limit == null ? MAX_PAGE : Math.clamp(limit, 1, MAX_PAGE);
        return new OffsetPageRequest(offset == null ? 0 : Math.max(offset, 0), size, sort);
    }

    public List<Review> getAll(Integer limit, Integer offset) {
        return reviewRepository.findAll(page(limit, offset, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .getContent();
    }

    public Review getOne(Long id) {
        return reviewRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Review not found with id " + id));
    }

    /** Les plus recents d'abord, plafonnes comme toute liste : un membre tres note ne sert pas tout. */
    public List<Review> byReviewee(String revieweeId, Integer limit, Integer offset) {
        return reviewRepository.findByRevieweeIdOrderByCreatedAtDescIdDesc(
                revieweeId, page(limit, offset, Sort.unsorted()));
    }

    public List<Review> byReviewer(String reviewerId, Integer limit, Integer offset) {
        return reviewRepository.findByReviewerIdOrderByCreatedAtDescIdDesc(
                reviewerId, page(limit, offset, Sort.unsorted()));
    }

    public List<Review> byTransaction(Long transactionId) {
        return reviewRepository.findByTransactionId(transactionId);
    }

    public Double averageForReviewee(String revieweeId) {
        return reviewRepository.averageRatingForReviewee(revieweeId);
    }

    /**
     * A review can only be written by a party to the transaction, about the other party.
     * Author and subject are both derived server-side, so a client cannot rate an arbitrary
     * user or sign a review with somebody else's id.
     */
    @Transactional
    public Review create(Review body, Jwt caller) {
        String reviewerId = caller.getSubject();

        if (body.getTransactionId() == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        // Garde-fou local d'abord : un double-clic depuis le front n'a pas a payer l'aller-retour
        // vers transactionservice. La recherche est cadree sur le sub de l'appelant, donc elle
        // ne revele rien qu'il ne sache deja sur ses propres avis.
        if (reviewRepository.existsByReviewerIdAndTransactionId(reviewerId, body.getTransactionId())) {
            throw new IllegalArgumentException("This transaction has already been reviewed");
        }
        TransactionSnapshot tx = transactionClient.fetchAsCaller(body.getTransactionId(), caller.getTokenValue());
        String counterpart = tx == null ? null : tx.counterpartOf(reviewerId);
        if (counterpart == null) {
            throw new AccessDeniedException("Caller is not a party to transaction " + body.getTransactionId());
        }

        Review review = new Review();
        review.setTransactionId(body.getTransactionId());
        review.setReviewerId(reviewerId);
        review.setReviewerName(caller.getClaimAsString("name"));
        review.setRevieweeId(counterpart);
        review.setRevieweeName(tx.counterpartNameOf(reviewerId));
        review.setRating(validRating(body.getRating()));
        review.setComment(body.getComment());
        try {
            // Flush immediat : c'est la contrainte unique qui tranche entre deux envois
            // simultanes, et son refus doit se lire ici plutot qu'au commit.
            return reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalArgumentException("This transaction has already been reviewed");
        }
    }

    /** Only the author may edit, and only the content -- never who it is about. */
    @Transactional
    public Review update(Long id, Review body, String callerSub) {
        Review existing = getOne(id);
        requireAuthor(existing, callerSub);

        existing.setRating(validRating(body.getRating()));
        existing.setComment(body.getComment());
        return reviewRepository.save(existing);
    }

    @Transactional
    public void delete(Long id, String callerSub) {
        Review existing = getOne(id);
        requireAuthor(existing, callerSub);
        reviewRepository.delete(existing);
    }

    private void requireAuthor(Review review, String callerSub) {
        if (review.getReviewerId() == null || !review.getReviewerId().equals(callerSub)) {
            throw new AccessDeniedException("Only the author may modify review " + review.getId());
        }
    }

    private Integer validRating(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        return rating;
    }
}

package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.client.TripClient;
import com.bagbuddy.transactionservice.client.TripSnapshot;
import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Actor;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.StatusPair;
import com.bagbuddy.transactionservice.service.TransactionStateMachine.Transition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TripClient tripClient;

    @Autowired
    private TransactionStateMachine stateMachine;

    /**
     * When true (the default, and the only safe setting in production), a transaction may only
     * reach the confirmed state once Stripe has actually confirmed the payment through the
     * signed webhook. Set it to false for local development, where stripeservice is disabled
     * and paying is simulated.
     */
    @Value("${PAYMENTS_REQUIRE_STRIPE:true}")
    private boolean requireStripe;

    public List<Transaction> getByUser(String userId) {
        return transactionRepository.findByBuyerIdOrSellerIdOrderByCreatedAtDesc(userId, userId);
    }

    public List<Transaction> getBySeller(String sellerId) {
        return transactionRepository.findBySellerId(sellerId);
    }

    public List<Transaction> getByBuyer(String buyerId) {
        return transactionRepository.findByBuyerId(buyerId);
    }

    public Long countByUser(String userId) {
        return transactionRepository.countByBuyerIdOrSellerId(userId, userId);
    }

    public Double getTotalEarnedBySeller(String sellerId) {
        return completedTotal(transactionRepository.findBySellerId(sellerId));
    }

    public Double getTotalSpentByBuyer(String buyerId) {
        return completedTotal(transactionRepository.findByBuyerId(buyerId));
    }

    private Double completedTotal(List<Transaction> transactions) {
        return transactions.stream()
                .filter(tx -> "completed".equalsIgnoreCase(tx.getBuyerStatus()) &&
                        "completed".equalsIgnoreCase(tx.getSellerStatus()))
                .mapToDouble(tx -> tx.getTotal().doubleValue())
                .sum();
    }

    /** Participant-only read: a transaction carries both parties' contact details. */
    public Transaction getOne(Long id, String callerSub) {
        Transaction tx = findOrThrow(id);
        requireParticipant(tx, callerSub);
        return tx;
    }

    /**
     * The booking is priced against the listing as tripservice owns it, so a client cannot
     * decide what it is going to pay by sending its own total, listingInfo or sellerId.
     */
    @Transactional
    public Transaction create(Transaction body, Jwt caller) {
        String buyerId = CallerIdentity.subOf(caller);

        if (body.getListingId() == null) {
            throw new IllegalArgumentException("listingId is required");
        }
        BigDecimal weight = body.getWeight();
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }

        TripSnapshot trip = tripClient.fetch(body.getListingId());
        if (trip == null || trip.getUserId() == null) {
            throw new NoSuchElementException("Listing not found: " + body.getListingId());
        }
        if (buyerId.equals(trip.getUserId())) {
            throw new IllegalArgumentException("A traveller cannot book their own listing");
        }
        if (!Boolean.TRUE.equals(trip.getActive())) {
            throw new IllegalArgumentException("Listing is no longer available");
        }
        if (trip.getPricePerKg() == null) {
            throw new IllegalArgumentException("Listing has no price");
        }

        if (trip.getRemainingWeight() == null || weight.compareTo(trip.getRemainingWeight()) > 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }

        Transaction tx = new Transaction();
        tx.setListingId(trip.getId());
        tx.setListingInfo(listingInfoOf(trip));
        tx.setBuyerId(buyerId);
        tx.setBuyerInfo(CallerIdentity.fromToken(caller, body.getBuyerInfo()));
        tx.setSellerId(trip.getUserId());
        tx.setWeight(weight);
        tx.setTotal(trip.getPricePerKg().multiply(weight).setScale(2, RoundingMode.HALF_UP));
        StatusPair initial = stateMachine.initialPair();
        tx.setSellerStatus(initial.sellerStatus());
        tx.setBuyerStatus(initial.buyerStatus());
        tx.setBuyerReview(false);
        tx.setSellerReview(false);
        // Payment fields are only ever written by the Stripe webhook (markPaid).
        return transactionRepository.save(tx);
    }

    /**
     * Moves the transaction through the state machine. Both status columns travel together --
     * that is how the flow is modelled -- so what is checked here is that the requested move is
     * a real edge of the machine and that the caller is on the side allowed to take it. Money
     * fields are never taken from the body: a re-priced request is recomputed from the listing,
     * and payment is settled through Stripe.
     */
    @Transactional
    public Transaction update(Long id, Transaction body, String callerSub) {
        Transaction existing = findOrThrow(id);
        requireParticipant(existing, callerSub);

        Actor actor = callerSub.equals(existing.getBuyerId()) ? Actor.BUYER : Actor.SELLER;

        // Each side owns its own "I have written my review" flag.
        if (actor == Actor.BUYER && body.getBuyerReview() != null) {
            existing.setBuyerReview(body.getBuyerReview());
        }
        if (actor == Actor.SELLER && body.getSellerReview() != null) {
            existing.setSellerReview(body.getSellerReview());
        }

        StatusPair from = new StatusPair(existing.getSellerStatus(), existing.getBuyerStatus());
        StatusPair to = new StatusPair(
                body.getSellerStatus() == null ? existing.getSellerStatus() : body.getSellerStatus(),
                body.getBuyerStatus() == null ? existing.getBuyerStatus() : body.getBuyerStatus());

        Transition transition = stateMachine.resolve(from, to, actor).orElse(null);
        if (transition == null) {
            return transactionRepository.save(existing);
        }

        switch (transition.effect()) {
            case REPRICE -> reprice(existing, body.getWeight());
            case RESERVE_CAPACITY -> reserveCapacity(existing);
            case SETTLE_PAYMENT -> settlePayment(existing);
            case NONE -> { }
        }

        existing.setSellerStatus(to.sellerStatus());
        existing.setBuyerStatus(to.buyerStatus());
        return transactionRepository.save(existing);
    }

    /** A new request with a different weight: the price is recomputed from the listing. */
    private void reprice(Transaction existing, BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }
        TripSnapshot trip = tripClient.fetch(existing.getListingId());
        if (trip == null || trip.getPricePerKg() == null) {
            throw new NoSuchElementException("Listing not found: " + existing.getListingId());
        }
        if (trip.getRemainingWeight() == null || weight.compareTo(trip.getRemainingWeight()) > 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }
        existing.setWeight(weight);
        existing.setTotal(trip.getPricePerKg().multiply(weight).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The seller accepting is the commitment point, so this is where the weight leaves the
     * listing -- decided by tripservice under a row lock, never by the caller.
     */
    private void reserveCapacity(Transaction existing) {
        TripSnapshot reserved = tripClient.reserve(existing.getListingId(), existing.getWeight());
        if (reserved == null) {
            throw new IllegalStateException("Capacity reservation returned nothing for listing "
                    + existing.getListingId());
        }
    }

    /**
     * Confirming a payment. With Stripe wired in, the transaction must already carry the
     * paidAt written by the signed webhook -- the browser saying "I paid" is not evidence.
     */
    private void settlePayment(Transaction existing) {
        if (existing.getPaidAt() != null) {
            return;
        }
        if (requireStripe) {
            throw new IllegalArgumentException(
                    "Payment has not been confirmed by Stripe for transaction " + existing.getId());
        }
        existing.setPaidAt(LocalDateTime.now());
    }

    /** Called only by the Stripe webhook, through the service-role internal endpoint. */
    @Transactional
    public Transaction markPaid(Long id, String paymentIntentId, Long amount, String currency) {
        Transaction tx = findOrThrow(id);
        if (tx.getPaidAt() != null) {
            // Stripe retries webhooks; recording the same payment twice must be a no-op.
            return tx;
        }
        tx.setStripePaymentIntentId(paymentIntentId);
        tx.setStripeAmount(amount);
        tx.setStripeCurrency(currency);
        tx.setPaidAt(LocalDateTime.now());
        return transactionRepository.save(tx);
    }

    @Transactional
    public void delete(Long id, String callerSub) {
        Transaction tx = findOrThrow(id);
        requireParticipant(tx, callerSub);
        transactionRepository.delete(tx);
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found with id " + id));
    }

    private void requireParticipant(Transaction tx, String callerSub) {
        if (!callerSub.equals(tx.getBuyerId()) && !callerSub.equals(tx.getSellerId())) {
            throw new AccessDeniedException("Caller is not a party to transaction " + tx.getId());
        }
    }

    private ListingInfo listingInfoOf(TripSnapshot trip) {
        ListingInfo info = new ListingInfo();
        info.setDepartureAirport(trip.getDepartureAirport());
        info.setArrivalAirport(trip.getArrivalAirport());
        info.setDepartureDate(trip.getDepartureDate() == null ? null : trip.getDepartureDate().toString());
        info.setArrivalDate(trip.getArrivalDate() == null ? null : trip.getArrivalDate().toString());
        info.setTotalWeightAvailable(trip.getTotalWeightAvailable() == null ? 0d : trip.getTotalWeightAvailable().doubleValue());
        info.setRemainingWeight(trip.getRemainingWeight() == null ? 0d : trip.getRemainingWeight().doubleValue());
        info.setPricePerKg(trip.getPricePerKg().doubleValue());
        info.setConditions(trip.getConditions());
        info.setCreatedAt(trip.getCreatedAt() == null ? LocalDateTime.now().toString() : trip.getCreatedAt().toString());
        info.setSellerUserInfo(sellerInfoOf(trip.getUserInfo()));
        return info;
    }

    private UserInfo sellerInfoOf(TripSnapshot.SellerInfo source) {
        UserInfo info = new UserInfo();
        if (source == null) {
            return info;
        }
        info.setSub(source.getSub());
        info.setEmail(source.getEmail());
        info.setEmail_verified(source.isEmail_verified());
        info.setFamily_name(source.getFamily_name());
        info.setGiven_name(source.getGiven_name());
        info.setName(source.getName());
        info.setUsername(source.getUsername());
        info.setBio(source.getBio());
        info.setLocation(source.getLocation());
        info.setPhone(source.getPhone());
        return info;
    }
}

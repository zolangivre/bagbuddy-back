package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.security.CallerIdentity;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;

    public TripService(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    /** Plafond dur : une lecture non filtree ne doit jamais pouvoir ramener toute la table. */
    public static final int MAX_PAGE = 200;

    /** limit/offset optionnels ; sans eux la page vaut MAX_PAGE. */
    private static PageRequest page(Integer limit, Integer offset) {
        int size = limit == null ? MAX_PAGE : Math.clamp(limit, 1, MAX_PAGE);
        return PageRequest.of(offset == null ? 0 : Math.max(offset, 0) / size, size);
    }

    public List<Trip> getAllTrips(Integer limit, Integer offset) {
        return tripRepository.findAllByOrderByCreatedAtDesc(page(limit, offset));
    }

    /**
     * Filtre en base et non en memoire : la version precedente chargeait toute la
     * table avant d'ecarter les lignes en Java, ce qui ne tient pas passe quelques
     * centaines d'annonces.
     */
    public List<Trip> getActiveTrips(Integer limit, Integer offset) {
        return tripRepository.findActive(LocalDateTime.now(), page(limit, offset));
    }

    public List<Trip> getInactiveTrips(Integer limit, Integer offset) {
        return tripRepository.findInactive(LocalDateTime.now(), page(limit, offset));
    }

    private boolean isActive(Trip trip) {
        return trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0 &&
                trip.getDepartureDate().isAfter(LocalDateTime.now());
    }

    public Trip getTripById(Long id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));
    }

    public List<Trip> getTripsByUserId(String userId) {
        return tripRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * The owner is taken from the access token, so a client cannot publish a trip under
     * another user's identity by forging userId / userInfo.sub in the request body.
     */
    @Transactional
    public Trip createTrip(Trip trip, Jwt caller) {
        trip.setId(null);
        trip.setUserId(CallerIdentity.subOf(caller));
        trip.setUserInfo(CallerIdentity.fromToken(caller, trip.getUserInfo()));

        // L'inventaire est decide par le serveur : une annonce neuve a toute sa capacite
        // disponible. Le client ne nomme jamais remainingWeight, il n'est pas dans TripInput.
        trip.setRemainingWeight(trip.getTotalWeightAvailable());

        // 'active' n'est pas calcule ici : TripListener le recalcule en @PrePersist et
        // ecraserait la valeur. Une seule formule, un seul endroit.
        return tripRepository.save(trip);
    }

    @Transactional
    public Trip updateTrip(Long id, Trip tripDetails, String callerSub) {
        Trip existingTrip = getTripById(id);
        requireOwner(existingTrip, callerSub);

        existingTrip.setDepartureAirport(tripDetails.getDepartureAirport());
        existingTrip.setArrivalAirport(tripDetails.getArrivalAirport());
        existingTrip.setDepartureDate(tripDetails.getDepartureDate());
        existingTrip.setArrivalDate(tripDetails.getArrivalDate());
        // La capacite restante suit la capacite totale par difference : agrandir l'annonce
        // ajoute autant de disponible, la reduire en retire autant. Le vendeur ne peut donc
        // pas se recrediter le poids deja vendu en nommant directement remainingWeight --
        // ce que le row lock de reserveCapacity() defend par ailleurs.
        existingTrip.setRemainingWeight(
                shiftedRemaining(existingTrip, tripDetails.getTotalWeightAvailable()));
        existingTrip.setTotalWeightAvailable(tripDetails.getTotalWeightAvailable());
        existingTrip.setPricePerKg(tripDetails.getPricePerKg());
        existingTrip.setConditions(tripDetails.getConditions());
        existingTrip.setStripeAccountId(tripDetails.getStripeAccountId());

        // userId / userInfo are deliberately not copied: ownership is immutable.
        return tripRepository.save(existingTrip);
    }

    @Transactional
    /** Reporte sur la capacite restante la variation de la capacite totale, sans passer sous zero. */
    private static BigDecimal shiftedRemaining(Trip existing, BigDecimal newTotal) {
        BigDecimal oldTotal = existing.getTotalWeightAvailable();
        BigDecimal remaining = existing.getRemainingWeight();
        if (newTotal == null || oldTotal == null || remaining == null) {
            return newTotal;
        }
        BigDecimal shifted = remaining.add(newTotal.subtract(oldTotal));
        return shifted.max(BigDecimal.ZERO).min(newTotal);
    }

    @Transactional
    public void deleteTrip(Long id, String callerSub) {
        Trip trip = getTripById(id);
        requireOwner(trip, callerSub);
        tripRepository.delete(trip);
    }

    public String getStripeAccountIdByUser(String userId) {
        // La plus recente annonce suffit : une projection LIMIT 1 plutot que tout l'historique.
        return tripRepository.findLatestStripeAccountId(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
    }

    /**
     * Decrements the remaining capacity of a listing. Called by transactionservice when a
     * booking is created: capacity is inventory, so it is decided here under a row lock and
     * never by whoever happens to be sending the request.
     */
    @Transactional
    public Trip reserveCapacity(Long id, BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));

        if (!isActive(trip)) {
            throw new IllegalArgumentException("Listing is no longer available");
        }
        if (trip.getRemainingWeight().compareTo(weight) < 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }
        trip.setRemainingWeight(trip.getRemainingWeight().subtract(weight));
        return tripRepository.save(trip);
    }

    private void requireOwner(Trip trip, String callerSub) {
        if (trip.getUserId() == null || !trip.getUserId().equals(callerSub)) {
            throw new AccessDeniedException("Caller does not own trip " + trip.getId());
        }
    }
}

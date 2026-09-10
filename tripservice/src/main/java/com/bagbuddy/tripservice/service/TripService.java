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

    public List<Trip> getAllTrips() {
        return tripRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Filtre en base et non en memoire : la version precedente chargeait toute la
     * table avant d'ecarter les lignes en Java, ce qui ne tient pas passe quelques
     * centaines d'annonces.
     */
    public List<Trip> getActiveTrips() {
        return tripRepository.findActive(LocalDateTime.now());
    }

    public List<Trip> getInactiveTrips() {
        return tripRepository.findInactive(LocalDateTime.now());
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
        existingTrip.setTotalWeightAvailable(tripDetails.getTotalWeightAvailable());
        existingTrip.setRemainingWeight(tripDetails.getRemainingWeight());
        existingTrip.setPricePerKg(tripDetails.getPricePerKg());
        existingTrip.setConditions(tripDetails.getConditions());
        existingTrip.setStripeAccountId(tripDetails.getStripeAccountId());

        // userId / userInfo are deliberately not copied: ownership is immutable.
        return tripRepository.save(existingTrip);
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

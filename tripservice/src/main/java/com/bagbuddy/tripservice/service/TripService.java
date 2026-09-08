package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.security.CallerIdentity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class TripService {

    @Autowired
    private TripRepository tripRepository;

    public List<Trip> getAllTrips() {
        return tripRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Trip> getActiveTrips() {
        return tripRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(this::isActive)
                .collect(Collectors.toList());
    }

    public List<Trip> getInactiveTrips() {
        return tripRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(trip -> !isActive(trip))
                .collect(Collectors.toList());
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
    public Trip createTrip(Trip trip, Jwt caller) {
        trip.setId(null);
        trip.setUserId(CallerIdentity.subOf(caller));
        trip.setUserInfo(CallerIdentity.fromToken(caller, trip.getUserInfo()));

        boolean stillAvailable = trip.getRemainingWeight() != null
                && trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0
                && trip.getDepartureDate() != null
                && trip.getDepartureDate().isAfter(LocalDateTime.now());
        trip.setActive(stillAvailable);
        return tripRepository.save(trip);
    }

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

    public void deleteTrip(Long id, String callerSub) {
        Trip trip = getTripById(id);
        requireOwner(trip, callerSub);
        tripRepository.delete(trip);
    }

    public String getStripeAccountIdByUser(String userId) {
        List<Trip> userTrips = getTripsByUserId(userId);
        if (userTrips.isEmpty()) {
            return null;
        }
        // userTrips is sorted by createdAt desc, so the first one is the latest.
        return userTrips.get(0).getStripeAccountId();
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

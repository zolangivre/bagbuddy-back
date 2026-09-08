package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
                .orElseThrow(() -> new RuntimeException("Trip not found with id " + id));
    }

    public List<Trip> getTripsByUserId(String userId) {
        return tripRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    public Trip createTrip(Trip trip) {
        if (trip.getUserInfo() != null) {
            trip.setUserId(trip.getUserInfo().getSub());
        }
        boolean stillAvailable = trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0 &&
                trip.getDepartureDate().isAfter(LocalDateTime.now());
        trip.setActive(stillAvailable);
        return tripRepository.save(trip);
    }

    public Trip updateTrip(Long id, Trip tripDetails) {
        Trip existingTrip = getTripById(id);

        existingTrip.setDepartureAirport(tripDetails.getDepartureAirport());
        existingTrip.setArrivalAirport(tripDetails.getArrivalAirport());
        existingTrip.setDepartureDate(tripDetails.getDepartureDate());
        existingTrip.setArrivalDate(tripDetails.getArrivalDate());
        existingTrip.setTotalWeightAvailable(tripDetails.getTotalWeightAvailable());
        existingTrip.setRemainingWeight(tripDetails.getRemainingWeight());
        existingTrip.setPricePerKg(tripDetails.getPricePerKg());
        existingTrip.setConditions(tripDetails.getConditions());
        existingTrip.setStripeAccountId(tripDetails.getStripeAccountId());

        return tripRepository.save(existingTrip);
    }

    public void deleteTrip(Long id) {
        tripRepository.deleteById(id);
    }

    public String getStripeAccountIdByUser(String userId) {
        List<Trip> userTrips = getTripsByUserId(userId); // utilise ton repo existant

        if (userTrips.isEmpty()) {
            return null; // ou lever une exception si nécessaire
        }

        // Retourne le dernier trip créé (userTrips est trié par createdAt desc)
        Trip latestTrip = userTrips.get(0);
        return latestTrip.getStripeAccountId();
    }
}
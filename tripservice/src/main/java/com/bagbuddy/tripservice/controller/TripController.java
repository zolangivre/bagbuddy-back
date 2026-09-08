package com.bagbuddy.tripservice.controller;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.service.TripService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/trips")
public class TripController {

    @Autowired
    private TripService tripService;

    @GetMapping
    public List<Trip> getAllTrips() {
        return tripService.getAllTrips();
    }

    @GetMapping("/{id}")
    public Trip getTripById(@PathVariable Long id) {
        return tripService.getTripById(id);
    }

    @GetMapping("/user/{userId}")
    public List<Trip> getTripsByUserId(@PathVariable String userId) {
        return tripService.getTripsByUserId(userId);
    }

    @PostMapping
    public Trip createTrip(@RequestBody Trip trip) {
        return tripService.createTrip(trip);
    }

    @PutMapping("/{id}")
    public Trip updateTrip(@PathVariable Long id, @RequestBody Trip tripDetails) {
        return tripService.updateTrip(id, tripDetails);
    }

    @DeleteMapping("/{id}")
    public void deleteTrip(@PathVariable Long id) {
        tripService.deleteTrip(id);
    }

    @GetMapping("/active")
    public List<Trip> getActiveTrips() {
        return tripService.getActiveTrips();
    }

    @GetMapping("/inactive")
    public List<Trip> getInactiveTrips() {
        return tripService.getInactiveTrips();
    }

    @GetMapping("/user/{userId}/stripe-account")
    public Map<String, String> getStripeAccountIdByUser(@PathVariable String userId) {
        String stripeAccountId = tripService.getStripeAccountIdByUser(userId);
        return Map.of("stripeAccountId", stripeAccountId != null ? stripeAccountId : "");
    }
}
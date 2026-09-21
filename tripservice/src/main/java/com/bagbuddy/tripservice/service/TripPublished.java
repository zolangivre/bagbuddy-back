package com.bagbuddy.tripservice.service;

/** Published inside createTrip's transaction; handled after commit by TripAlertNotifier. */
public record TripPublished(Long tripId) {
}

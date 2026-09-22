package com.bagbuddy.tripservice.dto;

/** Les tris proposes par le front (SortOption), faits en SQL plutot que sur une liste chargee. */
public enum TripSort {
    RECENT,
    EARLIEST_DEPARTURE,
    PRICE_LOW,
    PRICE_HIGH,
    WEIGHT_HIGH,
    WEIGHT_LOW
}

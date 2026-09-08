package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findAllByOrderByCreatedAtDesc();
    List<Trip> findAllByUserIdOrderByCreatedAtDesc(String userId);}
package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findAllByOrderByCreatedAtDesc();

    List<Trip> findAllByUserIdOrderByCreatedAtDesc(String userId);

    /** Row lock: two concurrent bookings must not both pass the capacity check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}

package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.TripReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface TripReservationRepository extends JpaRepository<TripReservation, Long> {

    Optional<TripReservation> findByTransactionId(Long transactionId);

    boolean existsByTripIdAndReleasedAtIsNull(Long tripId);

    @Modifying
    @Query("delete from TripReservation r where r.tripId = :tripId")
    void deleteByTripId(@Param("tripId") Long tripId);

    /** Poids encore pris sur l'annonce. Couverte par idx_trip_reservation_trip_active. */
    @Query("select coalesce(sum(r.weight), 0) from TripReservation r "
            + "where r.tripId = :tripId and r.releasedAt is null")
    BigDecimal sumActiveWeight(@Param("tripId") Long tripId);
}

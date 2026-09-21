package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.TripAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TripAlertRepository extends JpaRepository<TripAlert, Long> {

    List<TripAlert> findBySubOrderByCreatedAtDesc(String sub);

    List<TripAlert> findByDepartureAirportAndArrivalAirport(String departureAirport, String arrivalAirport);

    long countBySub(String sub);

    @Modifying
    @Query("delete from TripAlert a where a.id = :id and a.sub = :sub")
    int deleteOwned(Long id, String sub);
}

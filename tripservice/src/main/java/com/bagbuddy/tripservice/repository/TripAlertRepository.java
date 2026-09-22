package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.TripAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface TripAlertRepository extends JpaRepository<TripAlert, Long> {

    List<TripAlert> findBySubOrderByCreatedAtDesc(String sub);

    /**
     * Les alertes d'autres membres que l'auteur, dont les bornes de prix et de poids acceptent
     * deja l'annonce. Seule la fenetre de jours, qui depend du flexDays de chaque ligne, reste a
     * evaluer en Java : tout le reste est filtre par la base, index ix_trip_alert_route en tete.
     */
    @Query("""
            select a from TripAlert a
            where a.departureAirport = :from and a.arrivalAirport = :to
              and a.sub <> :author
              and (a.maxPricePerKg is null or a.maxPricePerKg >= :pricePerKg)
              and (a.minWeight is null or a.minWeight <= :remainingWeight)
            """)
    List<TripAlert> findCandidates(String from,
                                   String to,
                                   String author,
                                   BigDecimal pricePerKg,
                                   BigDecimal remainingWeight);

    long countBySub(String sub);

    @Modifying
    @Query("delete from TripAlert a where a.id = :id and a.sub = :sub")
    int deleteOwned(Long id, String sub);
}

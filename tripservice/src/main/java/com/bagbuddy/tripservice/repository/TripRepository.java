package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.model.Trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    /** id en second critere : a created_at egal, l'ordre doit rester le meme d'une page a l'autre. */
    List<Trip> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<Trip> findAllByUserIdOrderByCreatedAtDescIdDesc(String userId, Pageable pageable);

    /**
     * Annonces encore reservables. Le predicat reprend exactement celui du domaine
     * (capacite restante > 0 et depart dans le futur) et non la colonne 'active',
     * qui n'est recalculee qu'a l'ecriture et devient fausse des qu'une date de
     * depart est passee sans que la ligne ait bouge.
     */
    @Query("select t from Trip t where t.remainingWeight > 0 and t.departureDate > :now "
            + "order by t.createdAt desc, t.id desc")
    List<Trip> findActive(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Le complement exact de findActive, valeurs nulles comprises : une annonce
     * incomplete doit apparaitre ici plutot que de disparaitre des deux listes.
     */
    @Query("select t from Trip t where t.remainingWeight is null or t.remainingWeight <= 0 "
            + "or t.departureDate is null or t.departureDate <= :now order by t.createdAt desc, t.id desc")
    List<Trip> findInactive(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Projection d'une seule colonne, la plus recente d'abord : lire le compte de paiement
     * ne doit pas hydrater toutes les annonces publiees par l'utilisateur.
     * Appeler avec PageRequest.of(0, 1). Couverte par idx_trip_user_id_created_at.
     */
    @Query("select t.stripeAccountId from Trip t where t.userId = :userId order by t.createdAt desc")
    List<String> findLatestStripeAccountId(@Param("userId") String userId, Pageable pageable);

    /** Row lock: two concurrent bookings must not both pass the capacity check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}

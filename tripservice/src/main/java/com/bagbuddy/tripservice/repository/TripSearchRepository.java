package com.bagbuddy.tripservice.repository;

import com.bagbuddy.tripservice.dto.TripSort;
import com.bagbuddy.tripservice.model.Trip;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Recherche d'annonces a filtres optionnels. Criteria plutot qu'une @Query : chaque filtre absent
 * doit disparaitre de la requete, et non devenir un "or :param is null" que Postgres planifie mal.
 * La liste et les agregats partagent exactement les memes predicats.
 */
@Repository
public class TripSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Filtre normalise par TripService. Les bornes de date sont [departureFrom, departureBefore[ ;
     * now est l'instant de reference du predicat "encore reservable".
     */
    public record Criteria(
            String departureAirport,
            String arrivalAirport,
            LocalDateTime departureFrom,
            LocalDateTime departureBefore,
            BigDecimal minPricePerKg,
            BigDecimal maxPricePerKg,
            BigDecimal minWeight,
            BigDecimal maxWeight,
            TripSort sort,
            LocalDateTime now) {
    }

    public record Stats(long count, BigDecimal totalRemainingWeight, BigDecimal averagePricePerKg) {
    }

    public List<Trip> find(Criteria criteria, long offset, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Trip> query = cb.createQuery(Trip.class);
        Root<Trip> trip = query.from(Trip.class);
        query.select(trip)
                .where(predicates(cb, trip, criteria))
                .orderBy(order(cb, trip, criteria.sort()));
        return entityManager.createQuery(query)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(limit)
                .getResultList();
    }

    public Stats stats(Criteria criteria) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Trip> trip = query.from(Trip.class);
        query.multiselect(
                        cb.count(trip),
                        cb.sum(trip.<BigDecimal>get("remainingWeight")),
                        cb.avg(trip.<BigDecimal>get("pricePerKg")))
                .where(predicates(cb, trip, criteria));
        Tuple row = entityManager.createQuery(query).getSingleResult();

        Long count = row.get(0, Long.class);
        Object sum = row.get(1);
        Object avg = row.get(2);
        return new Stats(
                count == null ? 0 : count,
                sum == null ? BigDecimal.ZERO : new BigDecimal(sum.toString()),
                avg == null ? null : new BigDecimal(avg.toString()).setScale(2, RoundingMode.HALF_UP));
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<Trip> trip, Criteria c) {
        List<Predicate> where = new ArrayList<>();
        // Toujours : encore reservable. Meme predicat que findActive, et non la colonne active.
        where.add(cb.greaterThan(trip.get("departureDate"), c.now()));
        where.add(cb.greaterThan(trip.get("remainingWeight"), BigDecimal.ZERO));

        if (c.departureAirport() != null) {
            where.add(cb.equal(trip.get("departureAirport"), c.departureAirport()));
        }
        if (c.arrivalAirport() != null) {
            where.add(cb.equal(trip.get("arrivalAirport"), c.arrivalAirport()));
        }
        if (c.departureFrom() != null) {
            where.add(cb.greaterThanOrEqualTo(trip.get("departureDate"), c.departureFrom()));
        }
        if (c.departureBefore() != null) {
            where.add(cb.lessThan(trip.get("departureDate"), c.departureBefore()));
        }
        if (c.minPricePerKg() != null) {
            where.add(cb.greaterThanOrEqualTo(trip.get("pricePerKg"), c.minPricePerKg()));
        }
        if (c.maxPricePerKg() != null) {
            where.add(cb.lessThanOrEqualTo(trip.get("pricePerKg"), c.maxPricePerKg()));
        }
        if (c.minWeight() != null) {
            where.add(cb.greaterThanOrEqualTo(trip.get("remainingWeight"), c.minWeight()));
        }
        if (c.maxWeight() != null) {
            where.add(cb.lessThanOrEqualTo(trip.get("remainingWeight"), c.maxWeight()));
        }
        return where.toArray(Predicate[]::new);
    }

    /** id en dernier critere : a valeurs egales, l'ordre reste stable d'une page a l'autre. */
    private static List<Order> order(CriteriaBuilder cb, Root<Trip> trip, TripSort sort) {
        List<Order> order = new ArrayList<>();
        switch (sort == null ? TripSort.RECENT : sort) {
            case RECENT -> order.add(cb.desc(trip.get("createdAt")));
            case EARLIEST_DEPARTURE -> order.add(cb.asc(trip.get("departureDate")));
            case PRICE_LOW -> order.add(cb.asc(trip.get("pricePerKg")));
            case PRICE_HIGH -> order.add(cb.desc(trip.get("pricePerKg")));
            case WEIGHT_HIGH -> order.add(cb.desc(trip.get("remainingWeight")));
            case WEIGHT_LOW -> order.add(cb.asc(trip.get("remainingWeight")));
        }
        order.add(cb.desc(trip.get("id")));
        return order;
    }
}

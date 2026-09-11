package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.dto.TripSearchInput;
import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.TripReservation;
import com.bagbuddy.tripservice.repository.OffsetPageRequest;
import com.bagbuddy.tripservice.repository.TripRepository;
import com.bagbuddy.tripservice.repository.TripReservationRepository;
import com.bagbuddy.tripservice.repository.TripSearchRepository;
import com.bagbuddy.tripservice.security.CallerIdentity;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
// Lectures en readOnly par defaut : Hibernate n'garde pas de snapshot de
// dirty-checking et ne flushe pas. Chaque methode d'ecriture porte son propre
// @Transactional, qui surcharge ce defaut.
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;
    private final TripReservationRepository reservationRepository;
    private final TripSearchRepository searchRepository;

    public TripService(TripRepository tripRepository,
                       TripReservationRepository reservationRepository,
                       TripSearchRepository searchRepository) {
        this.tripRepository = tripRepository;
        this.reservationRepository = reservationRepository;
        this.searchRepository = searchRepository;
    }

    /** Tolerance de date maximale : au-dela, un filtre de date ne filtre plus grand-chose. */
    public static final int MAX_FLEX_DAYS = 30;

    /** Plafond dur : une lecture non filtree ne doit jamais pouvoir ramener toute la table. */
    public static final int MAX_PAGE = 200;

    /** limit/offset optionnels ; sans eux la page vaut MAX_PAGE. L'offset est pris tel quel. */
    private static OffsetPageRequest page(Integer limit, Integer offset) {
        int size = limit == null ? MAX_PAGE : Math.clamp(limit, 1, MAX_PAGE);
        return new OffsetPageRequest(offset == null ? 0 : Math.max(offset, 0), size);
    }

    public List<Trip> getAllTrips(Integer limit, Integer offset) {
        return tripRepository.findAllByOrderByCreatedAtDescIdDesc(page(limit, offset));
    }

    /**
     * Filtre en base et non en memoire : la version precedente chargeait toute la
     * table avant d'ecarter les lignes en Java, ce qui ne tient pas passe quelques
     * centaines d'annonces.
     */
    public List<Trip> getActiveTrips(Integer limit, Integer offset) {
        return tripRepository.findActive(LocalDateTime.now(), page(limit, offset));
    }

    public List<Trip> getInactiveTrips(Integer limit, Integer offset) {
        return tripRepository.findInactive(LocalDateTime.now(), page(limit, offset));
    }

    public record SearchPage(List<Trip> items, TripSearchRepository.Stats stats) {
    }

    /**
     * Annonces encore reservables, filtrees et triees en base. Remplace le chargement de toutes les
     * pages d'activeTrips suivi d'un filtrage dans le navigateur.
     */
    public SearchPage search(TripSearchInput filter, Integer limit, Integer offset) {
        TripSearchInput f = filter == null
                ? new TripSearchInput(null, null, null, null, null, null, null, null, null)
                : filter;

        LocalDateTime departureFrom = null;
        LocalDateTime departureBefore = null;
        if (f.date() != null && !f.date().isBlank()) {
            LocalDate day;
            try {
                day = LocalDate.parse(f.date().trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("date must be formatted as YYYY-MM-DD");
            }
            int flex = f.flexDays() == null ? 0 : f.flexDays();
            if (flex < 0 || flex > MAX_FLEX_DAYS) {
                throw new IllegalArgumentException("flexDays must be between 0 and " + MAX_FLEX_DAYS);
            }
            departureFrom = day.minusDays(flex).atStartOfDay();
            departureBefore = day.plusDays(flex + 1L).atStartOfDay();
        }
        requireNonNegative(f.minPricePerKg(), "minPricePerKg");
        requireNonNegative(f.maxPricePerKg(), "maxPricePerKg");
        requireNonNegative(f.minWeight(), "minWeight");
        requireNonNegative(f.maxWeight(), "maxWeight");

        TripSearchRepository.Criteria criteria = new TripSearchRepository.Criteria(
                normalizeAirport(f.departureAirport()),
                normalizeAirport(f.arrivalAirport()),
                departureFrom,
                departureBefore,
                f.minPricePerKg(),
                f.maxPricePerKg(),
                f.minWeight(),
                f.maxWeight(),
                f.sort(),
                LocalDateTime.now());
        OffsetPageRequest page = page(limit, offset);
        return new SearchPage(
                searchRepository.find(criteria, page.getOffset(), page.getPageSize()),
                searchRepository.stats(criteria));
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    /**
     * Codes IATA en majuscules, sans espaces : c'est ce qui rend la recherche par egalite fiable
     * (et indexable), quelle que soit la saisie. Vide = pas de filtre.
     */
    static String normalizeAirport(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isActive(Trip trip) {
        return trip.getRemainingWeight().compareTo(BigDecimal.ZERO) > 0 &&
                trip.getDepartureDate().isAfter(LocalDateTime.now());
    }

    public Trip getTripById(Long id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));
    }

    public List<Trip> getTripsByUserId(String userId, Integer limit, Integer offset) {
        return tripRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, page(limit, offset));
    }

    /**
     * The owner is taken from the access token, so a client cannot publish a trip under
     * another user's identity by forging userId / userInfo.sub in the request body.
     */
    @Transactional
    public Trip createTrip(Trip trip, Jwt caller) {
        trip.setId(null);
        trip.setUserId(CallerIdentity.subOf(caller));
        trip.setUserInfo(CallerIdentity.fromToken(caller, trip.getUserInfo()));
        trip.setDepartureAirport(normalizeAirport(trip.getDepartureAirport()));
        trip.setArrivalAirport(normalizeAirport(trip.getArrivalAirport()));

        // L'inventaire est decide par le serveur : une annonce neuve a toute sa capacite
        // disponible. Le client ne nomme jamais remainingWeight, il n'est pas dans TripInput.
        trip.setRemainingWeight(trip.getTotalWeightAvailable());

        // 'active' n'est pas calcule ici : TripListener le recalcule en @PrePersist et
        // ecraserait la valeur. Une seule formule, un seul endroit.
        return tripRepository.save(trip);
    }

    @Transactional
    public Trip updateTrip(Long id, Trip tripDetails, String callerSub) {
        // Meme verrou que reserveCapacity() : sans lui, une acceptation validee pendant que le
        // voyageur edite son annonce etait ecrasee par l'ancien remainingWeight a l'enregistrement.
        Trip existingTrip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));
        requireOwner(existingTrip, callerSub);

        BigDecimal newTotal = tripDetails.getTotalWeightAvailable();
        BigDecimal reserved = reservationRepository.sumActiveWeight(id);
        if (newTotal != null && newTotal.compareTo(reserved) < 0) {
            throw new IllegalArgumentException(
                    "Total weight cannot drop below the " + reserved.toPlainString() + " kg already booked");
        }

        existingTrip.setDepartureAirport(normalizeAirport(tripDetails.getDepartureAirport()));
        existingTrip.setArrivalAirport(normalizeAirport(tripDetails.getArrivalAirport()));
        existingTrip.setDepartureDate(tripDetails.getDepartureDate());
        existingTrip.setArrivalDate(tripDetails.getArrivalDate());
        // La capacite restante suit la capacite totale par difference : agrandir l'annonce
        // ajoute autant de disponible, la reduire en retire autant. Le vendeur ne peut donc
        // pas se recrediter le poids deja vendu en nommant directement remainingWeight --
        // ce que le row lock de reserveCapacity() defend par ailleurs.
        existingTrip.setRemainingWeight(
                shiftedRemaining(existingTrip, tripDetails.getTotalWeightAvailable()));
        existingTrip.setTotalWeightAvailable(tripDetails.getTotalWeightAvailable());
        existingTrip.setPricePerKg(tripDetails.getPricePerKg());
        existingTrip.setConditions(tripDetails.getConditions());
        // Omis = inchange, comme dans userservice : le front web ne lit ni n'envoie ce champ, et
        // modifier les dates d'une annonce effacait jusqu'ici son compte de paiement.
        if (tripDetails.getStripeAccountId() != null) {
            existingTrip.setStripeAccountId(tripDetails.getStripeAccountId());
        }

        // userId / userInfo are deliberately not copied: ownership is immutable.
        return tripRepository.save(existingTrip);
    }

    /** Reporte sur la capacite restante la variation de la capacite totale, sans passer sous zero. */
    private static BigDecimal shiftedRemaining(Trip existing, BigDecimal newTotal) {
        BigDecimal oldTotal = existing.getTotalWeightAvailable();
        BigDecimal remaining = existing.getRemainingWeight();
        if (newTotal == null || oldTotal == null || remaining == null) {
            return newTotal;
        }
        BigDecimal shifted = remaining.add(newTotal.subtract(oldTotal));
        return shifted.max(BigDecimal.ZERO).min(newTotal);
    }

    @Transactional
    public void deleteTrip(Long id, String callerSub) {
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));
        requireOwner(trip, callerSub);
        // Une reservation active, c'est un acheteur accepte, peut-etre deja debite : l'annonce ne
        // disparait pas sous lui. Annuler la transaction rend le poids et libere la suppression.
        if (reservationRepository.existsByTripIdAndReleasedAtIsNull(id)) {
            throw new IllegalArgumentException(
                    "This trip has accepted bookings; cancel them before deleting it");
        }
        // Restent les reservations rendues : un historique sans objet une fois l'annonce partie.
        reservationRepository.deleteByTripId(id);
        tripRepository.delete(trip);
    }

    public String getStripeAccountIdByUser(String userId) {
        // La plus recente annonce suffit : une projection LIMIT 1 plutot que tout l'historique.
        return tripRepository.findLatestStripeAccountId(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
    }

    /**
     * Decrements the remaining capacity of a listing on behalf of one transaction. Called by
     * transactionservice when the seller accepts: capacity is inventory, so it is decided here
     * under a row lock and never by whoever happens to be sending the request.
     *
     * Idempotent per transaction: replaying the same reservation (a double-click on "accept",
     * a retry after an ambiguous timeout) returns the listing without taking the weight twice.
     */
    @Transactional
    public Trip reserveCapacity(Long id, BigDecimal weight, Long transactionId) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("weight must be greater than zero");
        }
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        // Le verrou de l'annonce est pris avant de lire la reservation : deux rejeux simultanes
        // de la meme transaction se serialisent ici, le second voit la ligne du premier.
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));

        TripReservation reservation = reservationRepository.findByTransactionId(transactionId).orElse(null);
        if (reservation != null && !id.equals(reservation.getTripId())) {
            throw new IllegalArgumentException(
                    "Transaction " + transactionId + " already holds a reservation on another trip");
        }
        if (reservation != null && reservation.isActive()) {
            if (reservation.getWeight().compareTo(weight) != 0) {
                throw new IllegalArgumentException(
                        "Transaction " + transactionId + " already holds a different weight");
            }
            return trip;
        }

        if (!isActive(trip)) {
            throw new IllegalArgumentException("Listing is no longer available");
        }
        if (trip.getRemainingWeight().compareTo(weight) < 0) {
            throw new IllegalArgumentException("Requested weight exceeds the remaining capacity");
        }
        trip.setRemainingWeight(trip.getRemainingWeight().subtract(weight));

        // Une reservation rendue puis reprise (demande refusee, re-tarifee, acceptee a nouveau)
        // reutilise sa ligne : transaction_id est unique.
        if (reservation == null) {
            reservation = new TripReservation();
            reservation.setTripId(id);
            reservation.setTransactionId(transactionId);
        }
        reservation.setWeight(weight);
        reservation.setReleasedAt(null);
        reservationRepository.save(reservation);
        return tripRepository.save(trip);
    }

    /**
     * Gives a transaction's weight back to the listing, once. Called by transactionservice when an
     * accepted or paid booking is cancelled. Idempotent: releasing an already released reservation,
     * or one that never existed, changes nothing -- the caller may replay it safely.
     */
    @Transactional
    public Trip releaseCapacity(Long id, Long transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Trip not found with id " + id));

        TripReservation reservation = reservationRepository.findByTransactionId(transactionId).orElse(null);
        if (reservation == null || !reservation.isActive() || !id.equals(reservation.getTripId())) {
            return trip;
        }
        BigDecimal restored = trip.getRemainingWeight() == null
                ? reservation.getWeight()
                : trip.getRemainingWeight().add(reservation.getWeight());
        // Jamais au-dessus de la capacite totale, qu'un voyageur a pu reduire entre-temps.
        if (trip.getTotalWeightAvailable() != null) {
            restored = restored.min(trip.getTotalWeightAvailable());
        }
        trip.setRemainingWeight(restored);
        reservation.setReleasedAt(LocalDateTime.now());
        reservationRepository.save(reservation);
        return tripRepository.save(trip);
    }

    private void requireOwner(Trip trip, String callerSub) {
        if (trip.getUserId() == null || !trip.getUserId().equals(callerSub)) {
            throw new AccessDeniedException("Caller does not own trip " + trip.getId());
        }
    }
}

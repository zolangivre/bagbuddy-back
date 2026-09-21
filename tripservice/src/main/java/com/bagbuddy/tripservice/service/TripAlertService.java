package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.dto.TripAlertInput;
import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.TripAlert;
import com.bagbuddy.tripservice.repository.TripAlertRepository;
import com.bagbuddy.tripservice.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Alertes de trajet : la recherche qu'un membre laisse ouverte. Toutes les operations agissent sur
 * le sub du jeton ; l'email de l'alerte est celui du jeton a sa creation.
 *
 * La correspondance reprend la semantique de searchTrips : trajet exact (codes IATA en
 * majuscules), fenetre de jours calendaires autour de la date, prix maximum et poids minimum.
 */
@Service
public class TripAlertService {

    private static final Pattern IATA = Pattern.compile("[A-Z]{3}");

    private final TripAlertRepository repository;
    private final int maxPerMember;

    public TripAlertService(TripAlertRepository repository,
                            @Value("${bagbuddy.alerts.max-per-member:10}") int maxPerMember) {
        this.repository = repository;
        this.maxPerMember = maxPerMember;
    }

    @Transactional(readOnly = true)
    public List<TripAlert> mine(String sub) {
        return repository.findBySubOrderByCreatedAtDesc(sub);
    }

    @Transactional
    public TripAlert create(Jwt caller, TripAlertInput input) {
        String email = caller.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new BusinessException("alert_needs_email", "An alert needs an email address on the account.");
        }
        String from = TripService.normalizeAirport(input.departureAirport());
        String to = TripService.normalizeAirport(input.arrivalAirport());
        if (from == null || to == null || !IATA.matcher(from).matches() || !IATA.matcher(to).matches()
                || from.equals(to)) {
            throw new BusinessException("alert_invalid_route", "An alert needs two different airport codes.");
        }
        int flex = input.flexDays() == null ? 0 : input.flexDays();
        if (flex < 0 || flex > TripService.MAX_FLEX_DAYS) {
            throw new BusinessException("alert_invalid_flex", "flexDays must be between 0 and " + TripService.MAX_FLEX_DAYS);
        }
        if (repository.countBySub(caller.getSubject()) >= maxPerMember) {
            throw new BusinessException("too_many_alerts", "A member can keep at most " + maxPerMember + " alerts.");
        }

        TripAlert alert = new TripAlert();
        alert.setSub(caller.getSubject());
        alert.setEmail(email);
        alert.setLanguage("fr".equals(input.language()) ? "fr" : "en");
        alert.setDepartureAirport(from);
        alert.setArrivalAirport(to);
        alert.setDepartureDay(parseDay(input.date()));
        alert.setFlexDays(alert.getDepartureDay() == null ? 0 : flex);
        alert.setMaxPricePerKg(positiveOrNull(input.maxPricePerKg()));
        alert.setMinWeight(positiveOrNull(input.minWeight()));
        return repository.save(alert);
    }

    /** Vrai si l'alerte existait et appartenait a l'appelant. */
    @Transactional
    public boolean delete(Long id, String sub) {
        return repository.deleteOwned(id, sub) > 0;
    }

    /** Les alertes d'autres membres que l'auteur de l'annonce, qui correspondent a celle-ci. */
    @Transactional(readOnly = true)
    public List<TripAlert> matching(Trip trip) {
        return repository.findByDepartureAirportAndArrivalAirport(trip.getDepartureAirport(), trip.getArrivalAirport())
                .stream()
                .filter(alert -> !alert.getSub().equals(trip.getUserId()))
                .filter(alert -> matches(alert, trip))
                .toList();
    }

    static boolean matches(TripAlert alert, Trip trip) {
        if (alert.getDepartureDay() != null) {
            if (trip.getDepartureDate() == null) {
                return false;
            }
            long gap = Math.abs(ChronoUnit.DAYS.between(alert.getDepartureDay(), trip.getDepartureDate().toLocalDate()));
            if (gap > alert.getFlexDays()) {
                return false;
            }
        }
        if (alert.getMaxPricePerKg() != null
                && (trip.getPricePerKg() == null || trip.getPricePerKg().compareTo(alert.getMaxPricePerKg()) > 0)) {
            return false;
        }
        return alert.getMinWeight() == null
                || (trip.getRemainingWeight() != null && trip.getRemainingWeight().compareTo(alert.getMinWeight()) >= 0);
    }

    private static LocalDate parseDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("alert_invalid_date", "date must be YYYY-MM-DD");
        }
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}

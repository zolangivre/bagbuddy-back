package com.bagbuddy.tripservice.service;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.model.TripAlert;
import com.bagbuddy.tripservice.repository.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Emails the members whose alert matches a listing that was just published.
 *
 * After commit and off the request thread, like the transaction notifications: a traveller
 * publishing a listing never waits for, nor fails because of, the emails it triggers. Each
 * recipient gets one email in the language of their alert, with no contact detail of the
 * traveller — the link opens the listing in the app.
 */
@Component
public class TripAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(TripAlertNotifier.class);

    private final TripRepository trips;
    private final TripAlertService alerts;
    private final JavaMailSender sender;
    private final String from;
    private final String frontUrl;
    private final boolean enabled;

    public TripAlertNotifier(TripRepository trips, TripAlertService alerts, JavaMailSender sender,
                             @Value("${bagbuddy.alerts.from}") String from,
                             @Value("${bagbuddy.alerts.front-url}") String frontUrl,
                             @Value("${bagbuddy.alerts.enabled:true}") boolean enabled) {
        this.trips = trips;
        this.alerts = alerts;
        this.sender = sender;
        this.from = from;
        this.frontUrl = frontUrl.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripPublished(TripPublished event) {
        if (!enabled) {
            return;
        }
        Trip trip = trips.findById(event.tripId()).orElse(null);
        if (trip == null) {
            return;
        }
        for (TripAlert alert : alerts.matching(trip)) {
            try {
                sender.send(message(alert, trip));
            } catch (RuntimeException ex) {
                log.error("Alert {} could not be emailed for trip {}", alert.getId(), trip.getId(), ex);
            }
        }
    }

    SimpleMailMessage message(TripAlert alert, Trip trip) {
        boolean french = "fr".equals(alert.getLanguage());
        String route = trip.getDepartureAirport() + " → " + trip.getArrivalAirport();
        String date = trip.getDepartureDate() == null ? "?" : trip.getDepartureDate().format(french
                ? DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)
                : DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH));
        String link = frontUrl + "/transaction-detail?listingId=" + trip.getId();
        String weight = trip.getRemainingWeight() == null ? "?" : trip.getRemainingWeight().stripTrailingZeros().toPlainString();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(alert.getEmail());
        if (french) {
            message.setSubject("Nouveau trajet " + route + " · BagBuddy");
            message.setText("""
                    Bonjour,

                    Une annonce correspond à votre alerte : %s, départ le %s, %s kg disponibles.

                    %s

                    Vous recevez cet email parce que vous avez créé une alerte pour ce trajet. Vous \
                    pouvez la supprimer depuis votre profil.

                    L'équipe BagBuddy
                    """.formatted(route, date, weight, link));
        } else {
            message.setSubject("New trip " + route + " · BagBuddy");
            message.setText("""
                    Hello,

                    A listing matches your alert: %s, departing %s, %s kg available.

                    %s

                    You are receiving this email because you created an alert for this route. You \
                    can delete it from your profile.

                    The BagBuddy team
                    """.formatted(route, date, weight, link));
        }
        return message;
    }
}

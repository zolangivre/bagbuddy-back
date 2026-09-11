package com.bagbuddy.transactionservice.notification;

import com.bagbuddy.transactionservice.notification.TransactionNotifier.Notice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Writes and sends the transaction emails.
 *
 * Bilingual, French then English: this service does not know which language a member uses on
 * the front (the choice lives in their browser), and a wrong guess would be worse than both.
 * Plain text, no amount: a total without its currency conversion would contradict what the app
 * shows, and the link to the transaction carries the details.
 */
@Component
public class TransactionMailer {

    private static final DateTimeFormatter FRENCH_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter ENGLISH_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH);

    private final JavaMailSender sender;
    private final String from;
    private final String frontUrl;

    public TransactionMailer(JavaMailSender sender,
                             @Value("${bagbuddy.notifications.from}") String from,
                             @Value("${bagbuddy.notifications.front-url}") String frontUrl) {
        this.sender = sender;
        this.from = from;
        this.frontUrl = frontUrl.replaceAll("/+$", "");
    }

    public void send(Notice notice, TransactionStatusChanged event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(notice.recipient().email());
        message.setSubject(subject(notice, event));
        message.setText(body(notice, event));
        sender.send(message);
    }

    public String subject(Notice notice, TransactionStatusChanged event) {
        String route = route(event);
        return switch (notice.kind()) {
            case NEW_REQUEST -> "Nouvelle demande de réservation · New booking request · " + route;
            case REQUEST_ACCEPTED -> "Demande acceptée · Request accepted · " + route;
            case REQUEST_DECLINED -> "Demande refusée · Request declined · " + route;
            case PAYMENT_CONFIRMED -> "Paiement reçu · Payment received · " + route;
            case COMPLETED -> "Transaction terminée · Transaction completed · " + route;
            case CANCELLED -> "Transaction annulée · Transaction cancelled · " + route;
        };
    }

    public String body(Notice notice, TransactionStatusChanged event) {
        String link = frontUrl + "/transaction-detail?transactionId=" + event.transactionId();
        String counterpart = notice.counterpart().firstName();
        String otherFr = orElse(counterpart, "L'autre membre");
        String otherEn = orElse(counterpart, "The other member");
        String weight = weight(event.weight());
        String route = route(event);
        String dateFr = date(event.departureDate(), FRENCH_DATE);
        String dateEn = date(event.departureDate(), ENGLISH_DATE);

        String french = switch (notice.kind()) {
            case NEW_REQUEST -> "%s souhaite réserver %s kg sur votre vol %s du %s.\nAcceptez ou refusez la demande depuis l'application."
                    .formatted(otherFr, weight, route, dateFr);
            case REQUEST_ACCEPTED -> "%s a accepté votre demande de %s kg sur le vol %s du %s.\nIl ne reste plus qu'à payer pour confirmer la réservation."
                    .formatted(otherFr, weight, route, dateFr);
            case REQUEST_DECLINED -> "%s a refusé votre demande de %s kg sur le vol %s du %s.\nVous pouvez envoyer une nouvelle demande, par exemple pour un autre poids."
                    .formatted(otherFr, weight, route, dateFr);
            case PAYMENT_CONFIRMED -> "%s a payé sa réservation de %s kg sur votre vol %s du %s.\nLa transaction est confirmée : il reste à organiser la remise."
                    .formatted(otherFr, weight, route, dateFr);
            case COMPLETED -> "%s a indiqué que la transaction pour le vol %s du %s est terminée.\nPensez à laisser un avis."
                    .formatted(otherFr, route, dateFr);
            case CANCELLED -> "%s a annulé la transaction de %s kg sur le vol %s du %s."
                    .formatted(otherFr, weight, route, dateFr);
        };
        String english = switch (notice.kind()) {
            case NEW_REQUEST -> "%s would like to book %s kg on your %s flight on %s.\nAccept or decline the request in the app."
                    .formatted(otherEn, weight, route, dateEn);
            case REQUEST_ACCEPTED -> "%s accepted your request for %s kg on the %s flight on %s.\nPay to confirm the booking."
                    .formatted(otherEn, weight, route, dateEn);
            case REQUEST_DECLINED -> "%s declined your request for %s kg on the %s flight on %s.\nYou can send a new request, for instance for another weight."
                    .formatted(otherEn, weight, route, dateEn);
            case PAYMENT_CONFIRMED -> "%s paid for %s kg on your %s flight on %s.\nThe transaction is confirmed: all that is left is the handover."
                    .formatted(otherEn, weight, route, dateEn);
            case COMPLETED -> "%s marked the transaction for the %s flight on %s as completed.\nDon't forget to leave a review."
                    .formatted(otherEn, route, dateEn);
            case CANCELLED -> "%s cancelled the transaction for %s kg on the %s flight on %s."
                    .formatted(otherEn, weight, route, dateEn);
        };

        return """
                %s

                %s
                %s

                ---

                %s

                %s
                %s

                L'équipe BagBuddy · The BagBuddy team
                """.formatted(
                greeting(notice.recipient().firstName(), "Bonjour"), french, link,
                greeting(notice.recipient().firstName(), "Hello"), english, link);
    }

    private static String greeting(String firstName, String hello) {
        return firstName == null || firstName.isBlank() ? hello + "," : hello + " " + firstName + ",";
    }

    /** Le prenom de l'autre partie, ou une formule neutre s'il manque a l'instantane. */
    private static String orElse(String firstName, String fallback) {
        return firstName == null || firstName.isBlank() ? fallback : firstName;
    }

    private static String route(TransactionStatusChanged event) {
        return orDash(event.departureAirport()) + " → " + orDash(event.arrivalAirport());
    }

    private static String weight(BigDecimal weight) {
        return weight == null ? "?" : weight.stripTrailingZeros().toPlainString();
    }

    private static String date(String isoDateTime, DateTimeFormatter format) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "?";
        }
        try {
            return LocalDateTime.parse(isoDateTime).format(format);
        } catch (DateTimeParseException ex) {
            return isoDateTime;
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}

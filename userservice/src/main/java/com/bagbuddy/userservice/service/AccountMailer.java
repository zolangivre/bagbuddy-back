package com.bagbuddy.userservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Account emails, in the language the visitor was using on the web front (French or English,
 * the two the front ships). Plain text on purpose: a link and two sentences, nothing a mail
 * client can mangle.
 */
@Component
public class AccountMailer {

    private final JavaMailSender sender;
    private final String from;

    public AccountMailer(JavaMailSender sender, @Value("${bagbuddy.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    public void sendPasswordReset(String to, String firstName, String link, Duration validity,
                                  String language) {
        boolean french = "fr".equals(language);
        long minutes = validity.toMinutes();
        if (french) {
            send(to, "Réinitialiser votre mot de passe BagBuddy", """
                    %s

                    Une réinitialisation du mot de passe de votre compte BagBuddy a été demandée. \
                    Pour en choisir un nouveau, ouvrez ce lien dans les %d minutes :

                    %s

                    Si vous n'êtes pas à l'origine de cette demande, ignorez cet email : votre \
                    mot de passe actuel reste valable.

                    L'équipe BagBuddy
                    """.formatted(greeting(firstName, true), minutes, link));
        } else {
            send(to, "Reset your BagBuddy password", """
                    %s

                    Someone asked to reset the password of your BagBuddy account. To choose a new \
                    one, open this link within %d minutes:

                    %s

                    If you did not ask for this, ignore this email: your current password still \
                    works.

                    The BagBuddy team
                    """.formatted(greeting(firstName, false), minutes, link));
        }
    }

    public void sendEmailVerification(String to, String firstName, String link, Duration validity,
                                      String language) {
        boolean french = "fr".equals(language);
        long hours = Math.max(1, validity.toHours());
        if (french) {
            send(to, "Confirmez votre adresse email BagBuddy", """
                    %s

                    Pour confirmer que cette adresse est bien la vôtre, ouvrez ce lien dans les \
                    %d heures :

                    %s

                    Une adresse vérifiée apparaît comme telle sur votre profil : les autres \
                    membres savent qu'ils échangent avec une vraie personne.

                    Si vous n'avez pas de compte BagBuddy, ignorez cet email.

                    L'équipe BagBuddy
                    """.formatted(greeting(firstName, true), hours, link));
        } else {
            send(to, "Confirm your BagBuddy email address", """
                    %s

                    To confirm this address is yours, open this link within %d hours:

                    %s

                    A verified address shows as such on your profile: other members know they \
                    are dealing with a real person.

                    If you do not have a BagBuddy account, ignore this email.

                    The BagBuddy team
                    """.formatted(greeting(firstName, false), hours, link));
        }
    }

    private static String greeting(String firstName, boolean french) {
        if (firstName == null || firstName.isBlank()) {
            return french ? "Bonjour," : "Hello,";
        }
        return (french ? "Bonjour " : "Hello ") + firstName + ",";
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }
}

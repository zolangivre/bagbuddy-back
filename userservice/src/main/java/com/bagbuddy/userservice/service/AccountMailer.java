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
        long minutes = validity.toMinutes();
        boolean french = "fr".equals(language);
        String greeting = firstName == null || firstName.isBlank()
                ? (french ? "Bonjour," : "Hello,")
                : (french ? "Bonjour " + firstName + "," : "Hello " + firstName + ",");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        if (french) {
            message.setSubject("Réinitialiser votre mot de passe BagBuddy");
            message.setText("""
                    %s

                    Une réinitialisation du mot de passe de votre compte BagBuddy a été demandée. \
                    Pour en choisir un nouveau, ouvrez ce lien dans les %d minutes :

                    %s

                    Si vous n'êtes pas à l'origine de cette demande, ignorez cet email : votre \
                    mot de passe actuel reste valable.

                    L'équipe BagBuddy
                    """.formatted(greeting, minutes, link));
        } else {
            message.setSubject("Reset your BagBuddy password");
            message.setText("""
                    %s

                    Someone asked to reset the password of your BagBuddy account. To choose a new \
                    one, open this link within %d minutes:

                    %s

                    If you did not ask for this, ignore this email: your current password still \
                    works.

                    The BagBuddy team
                    """.formatted(greeting, minutes, link));
        }
        sender.send(message);
    }
}

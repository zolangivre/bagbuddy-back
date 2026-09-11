package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.service.ReportService.ReportFiled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the moderators about each new report, once it is stored. The report stays in the
 * database whatever happens here: a failed email is logged, and the table is the reference.
 *
 * Internal email, in English: it goes to the team, not to a member.
 */
@Component
public class ModerationMailer {

    private static final Logger log = LoggerFactory.getLogger(ModerationMailer.class);

    private final JavaMailSender sender;
    private final String from;
    private final String moderators;

    public ModerationMailer(JavaMailSender sender,
                            @Value("${bagbuddy.mail.from}") String from,
                            @Value("${bagbuddy.moderation.email}") String moderators) {
        this.sender = sender;
        this.from = from;
        this.moderators = moderators;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportFiled(ReportFiled report) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(moderators);
            message.setSubject("[BagBuddy] Report #" + report.reportId() + " · " + report.reason());
            message.setText("""
                    Report #%d
                    Reason: %s
                    Reported member (sub): %s
                    Reporter (sub): %s
                    Reporter email: %s
                    Transaction: %s

                    Details:
                    %s
                    """.formatted(
                    report.reportId(), report.reason(), report.reportedSub(), report.reporterSub(),
                    report.reporterEmail() == null ? "-" : report.reporterEmail(),
                    report.transactionId() == null ? "-" : report.transactionId(),
                    report.details() == null ? "-" : report.details()));
            sender.send(message);
        } catch (RuntimeException ex) {
            log.error("Moderation email for report {} could not be sent", report.reportId(), ex);
        }
    }
}

package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.dto.ReportMemberRequest;
import com.bagbuddy.userservice.model.MemberReport;
import com.bagbuddy.userservice.repository.MemberReportRepository;

import com.bagbuddy.userservice.web.AccountException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Reports a member to the moderators. The reporter is always the caller (the token's sub);
 * the moderators are emailed once the report is stored (see ModerationMailer).
 *
 * The daily cap is per reporter: it keeps one account from burying the moderation inbox, and
 * it is generous enough that a member with a genuinely bad week is never stopped.
 */
@Service
public class ReportService {

    /** Published once a report is stored, handled after commit. */
    public record ReportFiled(Long reportId, String reporterSub, String reporterEmail,
                              String reportedSub, Long transactionId, MemberReport.Reason reason,
                              String details) {
    }

    private final MemberReportRepository repository;
    private final ApplicationEventPublisher events;
    private final int maxPerDay;

    public ReportService(MemberReportRepository repository, ApplicationEventPublisher events,
                         @Value("${bagbuddy.reports.max-per-day:10}") int maxPerDay) {
        this.repository = repository;
        this.events = events;
        this.maxPerDay = maxPerDay;
    }

    @Transactional
    public boolean report(String reporterSub, String reporterEmail, ReportMemberRequest request) {
        String reported = request.getReportedSub().trim();
        if (reported.equals(reporterSub)) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "cannot_report_self",
                    "A member cannot report themselves.");
        }
        if (repository.countByReporterSubAndCreatedAtAfter(reporterSub, LocalDateTime.now().minusDays(1))
                >= maxPerDay) {
            throw new AccountException(HttpStatus.TOO_MANY_REQUESTS, "too_many_reports",
                    "Too many reports in the last 24 hours.");
        }

        MemberReport report = new MemberReport();
        report.setReporterSub(reporterSub);
        report.setReportedSub(reported);
        report.setTransactionId(request.getTransactionId());
        report.setReason(request.getReason());
        String details = request.getDetails() == null ? null : request.getDetails().trim();
        report.setDetails(details == null || details.isEmpty() ? null : details);
        MemberReport saved = repository.save(report);

        events.publishEvent(new ReportFiled(saved.getId(), reporterSub, reporterEmail, reported,
                saved.getTransactionId(), saved.getReason(), saved.getDetails()));
        return true;
    }
}

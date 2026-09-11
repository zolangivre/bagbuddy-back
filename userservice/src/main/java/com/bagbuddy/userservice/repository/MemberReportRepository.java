package com.bagbuddy.userservice.repository;

import com.bagbuddy.userservice.model.MemberReport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface MemberReportRepository extends JpaRepository<MemberReport, Long> {

    long countByReporterSubAndCreatedAtAfter(String reporterSub, LocalDateTime after);
}

package com.bagbuddy.userservice.dto;

import com.bagbuddy.userservice.model.MemberReport;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReportMemberRequest {

    /** Keycloak sub of the member being reported. */
    @NotBlank
    @Size(max = 255)
    private String reportedSub;

    /** Optional: the transaction the report is about. */
    private Long transactionId;

    @NotNull
    private MemberReport.Reason reason;

    @Size(max = 2000)
    private String details;
}

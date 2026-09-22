package com.bagbuddy.transactionservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The status vocabulary, mirrored from the front app's TRANSACTION_STATUS enum.
 *
 * The values live in configuration rather than as constants so renaming a status is a
 * deployment change on both sides rather than a code change here. The shape of the state
 * machine itself is in {@link com.bagbuddy.transactionservice.service.TransactionStateMachine}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "bagbuddy.transaction.status")
public class TransactionStatusProperties {

    private String reservationReceived = "reservation_received";
    private String waitingForResponseBuyer = "waiting_for_response";
    private String waitingForResponseSeller = "waiting_for_response_seller";
    private String requestRejected = "request_rejected";
    private String awaitingPayment = "awaiting_payment";
    private String paymentRequired = "payment_required";
    private String confirmed = "confirmed";
    private String completed = "completed";
    private String cancelled = "cancelled";
}

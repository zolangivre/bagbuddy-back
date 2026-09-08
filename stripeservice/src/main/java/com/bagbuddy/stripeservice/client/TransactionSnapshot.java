package com.bagbuddy.stripeservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionSnapshot {
    private Long id;
    private String buyerId;
    private String sellerId;
    private BigDecimal total;
    private LocalDateTime paidAt;
    private String stripePaymentIntentId;
}

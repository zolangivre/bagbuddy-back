package com.bagbuddy.transactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The listing as tripservice owns it. This, not the request body, is the pricing authority. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TripSnapshot {

    private Long id;
    private String userId;
    private SellerInfo userInfo;
    private String departureAirport;
    private String arrivalAirport;
    private LocalDateTime departureDate;
    private LocalDateTime arrivalDate;
    private BigDecimal totalWeightAvailable;
    private BigDecimal remainingWeight;
    private BigDecimal pricePerKg;
    private Boolean active;
    private String conditions;
    private LocalDateTime createdAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SellerInfo {
        private String sub;
        private String email;
        private boolean email_verified;
        private String family_name;
        private String given_name;
        private String name;
        private String username;
        private String bio;
        private String location;
        private String phone;
    }
}

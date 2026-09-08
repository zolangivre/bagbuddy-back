package com.bagbuddy.transactionservice.model;

import jakarta.persistence.*;
import lombok.Data;

@Embeddable
@Data
public class ListingInfo {
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name="email_verified", column=@Column(name="seller_email_verified")),
            @AttributeOverride(name="email", column=@Column(name="seller_email")),
            @AttributeOverride(name="bio", column=@Column(name="seller_bio")),
            @AttributeOverride(name="family_name", column=@Column(name="seller_family_name")),
            @AttributeOverride(name="given_name", column=@Column(name="seller_given_name")),
            @AttributeOverride(name="name", column=@Column(name="seller_name")),
            @AttributeOverride(name="username", column=@Column(name="seller_username")),
            @AttributeOverride(name="sub", column=@Column(name="seller_sub")),
            @AttributeOverride(name="location", column=@Column(name="seller_location")),
            @AttributeOverride(name="phone", column=@Column(name="seller_phone"))
    })
    private UserInfo sellerUserInfo;
    private String departureAirport;
    private String arrivalAirport;
    private String departureDate;
    private String arrivalDate;
    private double totalWeightAvailable;
    private double remainingWeight;
    private double pricePerKg;
    private String conditions;
    private String createdAt;
}
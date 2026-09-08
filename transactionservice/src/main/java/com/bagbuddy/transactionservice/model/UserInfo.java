package com.bagbuddy.transactionservice.model;

import lombok.Data;
import jakarta.persistence.Embeddable;

@Embeddable
@Data
public class UserInfo {
    private String email;
    private boolean email_verified;
    private String family_name;
    private String given_name;
    private String name;
    private String username;
    private String sub; // ID of the user
    private String bio;
    private String location;
    private String phone;
}
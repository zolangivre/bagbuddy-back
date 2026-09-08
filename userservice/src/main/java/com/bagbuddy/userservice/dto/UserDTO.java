package com.bagbuddy.userservice.dto;

import lombok.Data;

@Data
public class UserDTO {
    private String id;
    private String email;
    private String username;
    private String firstName;
    private String lastName;
    private String bio;
    private String location;
    private String phone;
    private String password;
}
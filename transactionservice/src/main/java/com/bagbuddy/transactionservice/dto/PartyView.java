package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.UserInfo;

/**
 * Instantane d'une partie tel qu'expose par le schema. L'entite garde ses colonnes snake_case ;
 * seule la representation sur le fil passe en camelCase, comme le veut GraphQL.
 */
public record PartyView(
        String sub,
        String name,
        String givenName,
        String familyName,
        String username,
        boolean emailVerified,
        String bio,
        String location,
        String email,
        String phone) {

    public static PartyView of(UserInfo source) {
        if (source == null) {
            return null;
        }
        return new PartyView(
                source.getSub(),
                source.getName(),
                source.getGiven_name(),
                source.getFamily_name(),
                source.getUsername(),
                source.isEmail_verified(),
                source.getBio(),
                source.getLocation(),
                source.getEmail(),
                source.getPhone());
    }
}

package com.bagbuddy.tripservice.dto;

import com.bagbuddy.tripservice.model.UserInfo;
import lombok.Data;

/**
 * Instantane du voyageur tel qu'expose par le schema GraphQL. Les coordonnees (email, phone)
 * ne sont remplies que pour le proprietaire de l'annonce : les listes sont lisibles par tout
 * membre authentifie et ne doivent pas distribuer le carnet d'adresses.
 *
 * Les noms sont en camelCase, comme le veut la convention GraphQL : l'entite UserInfo garde
 * ses colonnes snake_case, seule la representation sur le fil change.
 */
@Data
public class UserInfoView {

    private String sub;
    private String name;
    private String givenName;
    private String familyName;
    private String username;
    private boolean emailVerified;
    private String bio;
    private String location;

    // Uniquement quand l'appelant possede l'annonce.
    private String email;
    private String phone;

    public static UserInfoView of(UserInfo source, boolean includeContactDetails) {
        if (source == null) {
            return null;
        }
        UserInfoView view = new UserInfoView();
        view.sub = source.getSub();
        view.name = source.getName();
        view.givenName = source.getGiven_name();
        view.familyName = source.getFamily_name();
        view.username = source.getUsername();
        view.emailVerified = source.isEmail_verified();
        view.bio = source.getBio();
        view.location = source.getLocation();
        if (includeContactDetails) {
            view.email = source.getEmail();
            view.phone = source.getPhone();
        }
        return view;
    }
}

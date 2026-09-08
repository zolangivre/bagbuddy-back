package com.bagbuddy.tripservice.dto;

import com.bagbuddy.tripservice.model.UserInfo;
import lombok.Data;

/**
 * Seller snapshot as exposed on the wire. Contact details (email, phone) are only filled in
 * for the owner of the trip: the browse endpoints are readable by every authenticated user
 * and must not hand out the whole address book.
 */
@Data
public class UserInfoView {

    private String sub;
    private String name;
    private String given_name;
    private String family_name;
    private String username;
    private boolean email_verified;
    private String bio;
    private String location;

    // Only populated when the caller owns the trip.
    private String email;
    private String phone;

    public static UserInfoView of(UserInfo source, boolean includeContactDetails) {
        if (source == null) {
            return null;
        }
        UserInfoView view = new UserInfoView();
        view.sub = source.getSub();
        view.name = source.getName();
        view.given_name = source.getGiven_name();
        view.family_name = source.getFamily_name();
        view.username = source.getUsername();
        view.email_verified = source.isEmail_verified();
        view.bio = source.getBio();
        view.location = source.getLocation();
        if (includeContactDetails) {
            view.email = source.getEmail();
            view.phone = source.getPhone();
        }
        return view;
    }
}

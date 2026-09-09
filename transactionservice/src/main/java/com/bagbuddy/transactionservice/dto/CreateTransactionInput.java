package com.bagbuddy.transactionservice.dto;

import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;

import java.math.BigDecimal;

/**
 * Entree de createTransaction. Ni total, ni sellerId, ni listingInfo : tout cela est resolu
 * contre l'annonce reelle detenue par tripservice. Le schema rend la regle verifiable.
 */
public record CreateTransactionInput(Long listingId, BigDecimal weight, ProfileInput profile) {

    public record ProfileInput(String bio, String location, String phone) {
    }

    public Transaction toTransaction() {
        Transaction tx = new Transaction();
        tx.setListingId(listingId);
        tx.setWeight(weight);
        if (profile != null) {
            UserInfo info = new UserInfo();
            info.setBio(profile.bio());
            info.setLocation(profile.location());
            info.setPhone(profile.phone());
            tx.setBuyerInfo(info);
        }
        return tx;
    }
}

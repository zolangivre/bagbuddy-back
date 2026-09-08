package com.bagbuddy.reviewservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionSnapshot {

    private Long id;
    private String buyerId;
    private String sellerId;
    private Party buyerInfo;
    private Listing listingInfo;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Party {
        private String sub;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Listing {
        private Party sellerUserInfo;
    }

    /** The other party, from the point of view of the caller. */
    public String counterpartOf(String callerSub) {
        if (callerSub.equals(buyerId)) {
            return sellerId;
        }
        if (callerSub.equals(sellerId)) {
            return buyerId;
        }
        return null;
    }

    public String counterpartNameOf(String callerSub) {
        if (callerSub.equals(buyerId)) {
            return listingInfo == null || listingInfo.getSellerUserInfo() == null
                    ? null : listingInfo.getSellerUserInfo().getName();
        }
        return buyerInfo == null ? null : buyerInfo.getName();
    }
}

package com.bagbuddy.tripservice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Une page de resultats et ce que le front calculait jusqu'ici sur toutes les annonces chargees :
 * le nombre de resultats, le poids disponible et le prix moyen. Les agregats portent sur tout le
 * filtre, pas sur la seule page.
 */
public record TripSearchResult(
        List<TripResponse> items,
        long totalCount,
        BigDecimal totalRemainingWeight,
        BigDecimal averagePricePerKg) {
}

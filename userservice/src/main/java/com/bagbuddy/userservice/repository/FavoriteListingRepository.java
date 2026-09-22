package com.bagbuddy.userservice.repository;

import com.bagbuddy.userservice.model.FavoriteListing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FavoriteListingRepository extends JpaRepository<FavoriteListing, Long> {

    List<FavoriteListing> findBySubOrderByCreatedAtDesc(String sub);

    boolean existsBySubAndListingId(String sub, Long listingId);

    long countBySub(String sub);

    @Modifying
    @Query("delete from FavoriteListing f where f.sub = :sub and f.listingId = :listingId")
    int deleteBySubAndListingId(String sub, Long listingId);
}

package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.model.FavoriteListing;
import com.bagbuddy.userservice.repository.FavoriteListingRepository;

import com.bagbuddy.userservice.web.AccountException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Listings a member put aside. Only ids are kept here: the listing itself is read from
 * tripservice (tripsByIds) when the front shows the list, so a changed price or a sold-out
 * listing is never stale.
 */
@Service
public class FavoriteService {

    private final FavoriteListingRepository repository;
    private final int max;

    public FavoriteService(FavoriteListingRepository repository,
                           @Value("${bagbuddy.favorites.max:200}") int max) {
        this.repository = repository;
        this.max = max;
    }

    @Transactional(readOnly = true)
    public List<Long> listingIds(String sub) {
        return repository.findBySubOrderByCreatedAtDesc(sub).stream()
                .map(FavoriteListing::getListingId)
                .toList();
    }

    /**
     * Idempotent: adding a listing already there answers true without a second row. The cap
     * matches what tripsByIds reads in one call.
     */
    @Transactional
    public boolean add(String sub, Long listingId) {
        if (repository.existsBySubAndListingId(sub, listingId)) {
            return true;
        }
        if (repository.countBySub(sub) >= max) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "too_many_favorites",
                    "A member can keep at most " + max + " favorite listings.");
        }
        FavoriteListing favorite = new FavoriteListing();
        favorite.setSub(sub);
        favorite.setListingId(listingId);
        try {
            repository.saveAndFlush(favorite);
        } catch (DataIntegrityViolationException raced) {
            // Deux clics simultanes : la contrainte unique a garde une seule ligne, c'est le but.
        }
        return true;
    }

    /** Idempotent as well: removing a listing that is not there is not an error. */
    @Transactional
    public boolean remove(String sub, Long listingId) {
        repository.deleteBySubAndListingId(sub, listingId);
        return true;
    }
}

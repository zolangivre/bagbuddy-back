package com.bagbuddy.tripservice.controller;

import com.bagbuddy.tripservice.model.Trip;
import com.bagbuddy.tripservice.service.TripService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Endpoints service-a-service, volontairement restes en REST : ils ne sont jamais appeles par
 * un navigateur mais par transactionservice, avec le role realm 'service' (voir SecurityConfig).
 * GraphQL n'apporterait rien ici -- une seule forme de reponse, un seul appelant -- et les
 * garder en REST evite de rendre l'endpoint /graphql accessible au jeton de service.
 */
@RestController
@RequestMapping("/trips/internal")
public class TripInternalController {

    private final TripService tripService;

    public TripInternalController(TripService tripService) {
        this.tripService = tripService;
    }

    /**
     * Annonce complete, instantane de contact du vendeur inclus : transactionservice tarifie
     * une reservation contre l'annonce reelle.
     */
    @GetMapping("/{id}")
    public Trip getTripInternal(@PathVariable Long id) {
        return tripService.getTripById(id);
    }

    /**
     * Reserve de la capacite pour une transaction et renvoie l'annonce telle qu'elle est
     * desormais. Rejouable : la meme transaction ne prend jamais le poids deux fois.
     */
    @PostMapping("/{id}/reserve")
    public Trip reserveCapacity(@PathVariable Long id, @RequestBody ReserveRequest body) {
        return tripService.reserveCapacity(id, body.weight(), body.transactionId());
    }

    /** Rend a l'annonce le poids d'une transaction annulee. Rejouable, sans effet la deuxieme fois. */
    @PostMapping("/{id}/release")
    public Trip releaseCapacity(@PathVariable Long id, @RequestBody ReleaseRequest body) {
        return tripService.releaseCapacity(id, body.transactionId());
    }

    public record ReserveRequest(BigDecimal weight, Long transactionId) {
    }

    public record ReleaseRequest(Long transactionId) {
    }
}

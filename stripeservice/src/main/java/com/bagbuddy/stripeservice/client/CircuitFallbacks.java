package com.bagbuddy.stripeservice.client;

import com.bagbuddy.stripeservice.web.ServiceUnavailableException;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Repli commun des appels sortants. Il ne fabrique aucune valeur de remplacement : les reponses
 * metier (introuvable, non-participant, requete refusee) remontent intactes, et tout le reste
 * devient une indisponibilite explicite plutot qu'une erreur interne opaque.
 *
 * Ces trois exceptions sont aussi les ignore-exceptions des circuits (application.yml).
 */
final class CircuitFallbacks {

    private CircuitFallbacks() {
    }

    static <T> Function<Throwable, T> failFast(String circuit) {
        return throwable -> {
            if (throwable instanceof NoSuchElementException
                    || throwable instanceof IllegalArgumentException
                    || throwable instanceof AccessDeniedException) {
                throw (RuntimeException) throwable;
            }
            throw new ServiceUnavailableException(circuit, throwable);
        };
    }
}

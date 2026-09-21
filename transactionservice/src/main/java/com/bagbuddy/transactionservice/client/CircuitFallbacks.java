package com.bagbuddy.transactionservice.client;

import com.bagbuddy.transactionservice.web.ServiceUnavailableException;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Repli commun des appels sortants. Il ne fabrique jamais de valeur de remplacement : on ne
 * tarife pas une reservation contre une annonce qu'on n'a pas lue, et un prix par defaut serait
 * pire qu'une erreur. Les reponses metier (introuvable, refuse, invalide) remontent intactes ;
 * tout le reste devient une indisponibilite explicite, que le coupe-circuit ne confonde pas avec
 * une regle du domaine.
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

package com.bagbuddy.stripeservice.gateway;

import com.bagbuddy.stripeservice.web.ServiceUnavailableException;

/**
 * Stripe n'a pas pu traiter la demande pour une raison qui n'est pas la demande elle-meme
 * (reseau, limite de debit, panne) : un nouvel essai a du sens, et l'appelant doit le savoir.
 * Une indisponibilite comme une autre : les gestionnaires d'erreur la traitent sans cas particulier.
 */
public class StripeUnavailableException extends ServiceUnavailableException {

    public StripeUnavailableException(Throwable cause) {
        super("stripe", cause);
    }
}

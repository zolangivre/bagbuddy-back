package com.bagbuddy.stripeservice.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ne couvre plus que le webhook Stripe, seul endpoint REST restant du service : un
 * @RestControllerAdvice ne s'applique qu'aux controleurs HTTP, jamais aux resolvers GraphQL,
 * dont les erreurs passent par GraphQlExceptionResolver.
 *
 * Stripe reessaie sur reponse non-2xx : renvoyer 403 sur une signature invalide et 400 sur un
 * payload illisible evite de faire passer pour une panne serveur ce qui n'en est pas une.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}

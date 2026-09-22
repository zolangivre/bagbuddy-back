package com.bagbuddy.tripservice.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Ne couvre plus que les endpoints service-a-service de /trips/internal, seul REST restant :
 * un @RestControllerAdvice ne s'applique qu'aux controleurs HTTP, jamais aux resolvers GraphQL,
 * dont les erreurs passent par GraphQlExceptionResolver.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Kept explicit so the generic handlers below never swallow an authorization failure
     * and downgrade it to a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}

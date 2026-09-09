package com.bagbuddy.transactionservice.web;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Ce que faisait ApiExceptionHandler pour REST : traduire les exceptions metier en reponses
 * comprehensibles. En GraphQL le transport est toujours 200 ; c'est le champ errors[].extensions
 * .classification qui porte le sens, et c'est lui que les clients doivent lire.
 *
 * Les exceptions non listees ici ne sont volontairement pas traduites : elles ressortent en
 * INTERNAL_ERROR avec un message generique, pour ne pas fuiter d'interne au client.
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof AccessDeniedException) {
            // Un appelant non authentifie qui bute sur une regle d'acces doit lire UNAUTHORIZED
            // et non FORBIDDEN : il lui manque un jeton, pas un droit.
            return error(env, isAuthenticated() ? ErrorType.FORBIDDEN : ErrorType.UNAUTHORIZED,
                    isAuthenticated() ? "Access denied" : "Authentication required");
        }
        if (ex instanceof AuthenticationException) {
            return error(env, ErrorType.UNAUTHORIZED, "Authentication required");
        }
        if (ex instanceof NoSuchElementException) {
            return error(env, ErrorType.NOT_FOUND, ex.getMessage());
        }
        if (ex instanceof ConstraintViolationException violation) {
            return error(env, ErrorType.BAD_REQUEST, violation.getConstraintViolations().stream()
                    .findFirst()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .orElse("Invalid input"));
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return error(env, ErrorType.BAD_REQUEST, ex.getMessage());
        }
        return null;
    }

    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static GraphQLError error(DataFetchingEnvironment env, ErrorType type, String message) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(type)
                .message(message == null ? type.name() : message)
                .build();
    }
}

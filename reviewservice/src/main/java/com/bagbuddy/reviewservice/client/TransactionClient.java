package com.bagbuddy.reviewservice.client;

import com.bagbuddy.reviewservice.web.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.FieldAccessException;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lit une transaction avec le jeton de l'appelant. transactionservice refuse deja de servir une
 * transaction a qui n'y a pas pris part : un refus ici est donc exactement la reponse voulue --
 * on ne note pas une affaire dont on n'a pas fait partie.
 *
 * L'appel passe par le schema GraphQL du service et ne demande que les champs reellement
 * utilises pour attribuer l'avis, la ou l'ancien GET /transactions/{id} rapatriait toute la
 * transaction, coordonnees des deux parties comprises.
 */
@Component
public class TransactionClient {

    private static final String CIRCUIT = "transactionservice";

    private static final String TRANSACTION_DOCUMENT = """
            query Transaction($id: ID!) {
                transaction(id: $id) {
                    id
                    buyerId
                    sellerId
                    buyerInfo { sub name }
                    listingInfo { sellerUserInfo { sub name } }
                }
            }
            """;

    private final HttpSyncGraphQlClient graphQlClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public TransactionClient(RestClient.Builder builder,
                             CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                             @Value("${bagbuddy.transaction-service.url}") String transactionServiceUrl) {
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.graphQlClient = HttpSyncGraphQlClient.builder(
                        builder.baseUrl(transactionServiceUrl + "/transactions/graphql"))
                .build();
    }

    public TransactionSnapshot fetchAsCaller(Long transactionId, String callerToken) {
        return circuitBreakerFactory.create(CIRCUIT)
                .run(() -> doFetchAsCaller(transactionId, callerToken), failFast());
    }

    private TransactionSnapshot doFetchAsCaller(Long transactionId, String callerToken) {
        try {
            return graphQlClient.mutate()
                    .headers(headers -> headers.setBearerAuth(callerToken))
                    .build()
                    .document(TRANSACTION_DOCUMENT)
                    .variable("id", transactionId)
                    .retrieveSync("transaction")
                    .toEntity(TransactionSnapshot.class);
        } catch (FieldAccessException ex) {
            // En GraphQL le transport reste 200 : le refus se lit dans errors[].classification.
            throw translate(ex.getResponse().getErrors(), transactionId);
        } catch (RestClientResponseException ex) {
            // Jeton absent ou invalide : rejete par la chaine de securite avant le schema.
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AccessDeniedException("Caller is not a party to transaction " + transactionId);
            }
            throw ex;
        }
    }

    /**
     * Le repli ne fabrique aucune valeur de remplacement : les reponses metier
     * (introuvable, non-participant) remontent intactes, et tout le reste devient
     * une indisponibilite explicite plutot qu'une erreur interne opaque.
     */
    private static <T> java.util.function.Function<Throwable, T> failFast() {
        return throwable -> {
            if (throwable instanceof NoSuchElementException
                    || throwable instanceof IllegalArgumentException
                    || throwable instanceof AccessDeniedException) {
                throw (RuntimeException) throwable;
            }
            throw new ServiceUnavailableException(CIRCUIT, throwable);
        };
    }

    private RuntimeException translate(List<ResponseError> errors, Long transactionId) {
        String classification = errors.isEmpty() ? null : classificationOf(errors.get(0));
        if ("FORBIDDEN".equals(classification) || "UNAUTHORIZED".equals(classification)) {
            return new AccessDeniedException("Caller is not a party to transaction " + transactionId);
        }
        if ("NOT_FOUND".equals(classification)) {
            return new NoSuchElementException("Transaction not found: " + transactionId);
        }
        return new IllegalStateException("transactionservice refused the read for transaction "
                + transactionId + " (" + classification + ")");
    }

    private static String classificationOf(ResponseError error) {
        Object classification = error.getExtensions().get("classification");
        return classification != null ? classification.toString() : String.valueOf(error.getErrorType());
    }
}

package com.bagbuddy.stripeservice.client;

import com.bagbuddy.stripeservice.web.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.FieldAccessException;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Deux chemins vers transactionservice, et deux protocoles, pour deux raisons differentes :
 *
 *  - la lecture passe par GraphQL avec le jeton de l'acheteur, pour que transactionservice
 *    applique lui-meme le controle de participation ;
 *  - la confirmation de paiement reste un POST REST sur l'endpoint interne, garde par le role
 *    realm 'service' : elle est declenchee par un webhook, ou aucun utilisateur n'est present.
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
                    total
                    sellerStatus
                    buyerStatus
                    paidAt
                }
            }
            """;

    private final HttpSyncGraphQlClient graphQlClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public TransactionClient(RestClient.Builder builder,
                             CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                             ServiceTokenProvider tokenProvider,
                             @Value("${bagbuddy.transaction-service.url}") String transactionServiceUrl) {
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.graphQlClient = HttpSyncGraphQlClient.builder(
                        builder.clone().baseUrl(transactionServiceUrl + "/transactions/graphql"))
                .build();
        this.restClient = builder.baseUrl(transactionServiceUrl).build();
        this.tokenProvider = tokenProvider;
    }

    /** Lu avec le jeton de l'acheteur, pour que transactionservice tranche la participation. */
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
            // Le transport reste 200 en GraphQL : le refus se lit dans errors[].classification.
            throw translate(ex.getResponse().getErrors(), transactionId);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new AccessDeniedException("Caller is not a party to transaction " + transactionId);
            }
            throw ex;
        }
    }

    /** Chemin webhook : Stripe n'a pas de jeton utilisateur, on passe par le role de service. */
    public void confirmPayment(Long transactionId, String paymentIntentId, Long amount, String currency) {
        restClient.post()
                .uri("/transactions/internal/{id}/payment", transactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.tokenValue())
                .body(Map.of(
                        "paymentIntentId", paymentIntentId,
                        "amount", amount,
                        "currency", currency))
                .retrieve()
                .toBodilessEntity();
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

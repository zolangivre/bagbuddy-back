package com.bagbuddy.stripeservice.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * Tout ce que le service demande a Stripe, en termes du domaine. Le SDK Stripe expose des
 * methodes statiques qu'on ne peut pas remplacer dans un test : cette interface est la couture
 * qui permet de verifier les regles (qui est paye, combien, jamais deux fois) sans reseau.
 *
 * Les montants sont en unites mineures (centimes), comme chez Stripe.
 */
public interface StripeGateway {

    /** @return le client secret, a remettre a Stripe.js */
    String createPaymentIntent(long amount, String currency, String transferGroup, Map<String, String> metadata);

    /** Le paiement effectif derriere un PaymentIntent reussi : la source d'un versement. */
    String latestChargeOf(String paymentIntentId);

    /** Un remboursement deja emis pour cette transaction, s'il en existe un. */
    Optional<String> findRefund(String paymentIntentId, String transactionId);

    /** @return l'identifiant du remboursement */
    String refund(String paymentIntentId, long amount, String transactionId);

    /** Un versement deja emis pour ce groupe (une transaction), s'il en existe un. */
    Optional<String> findTransfer(String transferGroup);

    /** @return l'identifiant du versement */
    String transfer(String destinationAccount, long amount, String currency, String sourceCharge,
                    String transferGroup, String transactionId);

    /** Cree un compte Connect Express pour un membre. @return l'identifiant acct_... */
    String createExpressAccount(String sub, String email, String country);

    /** Etat d'un compte connecte, ou vide s'il n'existe pas (ou plus) chez Stripe. */
    Optional<ConnectedAccount> account(String accountId);

    /** Lien d'onboarding a usage unique, a ouvrir dans le navigateur du membre. */
    String onboardingLink(String accountId, String refreshUrl, String returnUrl);

    record ConnectedAccount(boolean detailsSubmitted, boolean payoutsEnabled, boolean transfersActive) {
    }
}

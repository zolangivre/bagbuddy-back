package com.bagbuddy.stripeservice.gateway;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * L'implementation reelle, sur le SDK. Chaque ecriture porte une cle d'idempotence derivee de la
 * transaction : un appel rejoue dans les 24 h renvoie le meme objet Stripe au lieu d'en creer un
 * second. Au-dela, ce sont les recherches findRefund / findTransfer qui evitent le doublon.
 */
@Component
public class StripeSdkGateway implements StripeGateway {

    @Override
    public String createPaymentIntent(long amount, String currency, String transferGroup,
                                      Map<String, String> metadata) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(currency)
                .setTransferGroup(transferGroup)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .putAllMetadata(metadata)
                .build();
        return call(() -> PaymentIntent.create(params).getClientSecret());
    }

    @Override
    public String latestChargeOf(String paymentIntentId) {
        String charge = call(() -> PaymentIntent.retrieve(paymentIntentId).getLatestCharge());
        if (charge == null) {
            throw new IllegalArgumentException("PaymentIntent " + paymentIntentId + " has no charge");
        }
        return charge;
    }

    @Override
    public Optional<String> findRefund(String paymentIntentId, String transactionId) {
        RefundListParams params = RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(100L).build();
        return call(() -> Refund.list(params).getData().stream()
                .filter(refund -> refund.getMetadata() != null
                        && transactionId.equals(refund.getMetadata().get("transactionId")))
                .map(Refund::getId)
                .findFirst());
    }

    @Override
    public String refund(String paymentIntentId, long amount, String transactionId) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(amount)
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("transactionId", transactionId)
                .build();
        return call(() -> Refund.create(params, idempotent("refund-transaction-" + transactionId)).getId());
    }

    @Override
    public Optional<String> findTransfer(String transferGroup) {
        TransferListParams params = TransferListParams.builder().setTransferGroup(transferGroup).setLimit(1L).build();
        return call(() -> Transfer.list(params).getData().stream().map(Transfer::getId).findFirst());
    }

    @Override
    public String transfer(String destinationAccount, long amount, String currency, String sourceCharge,
                           String transferGroup, String transactionId) {
        TransferCreateParams params = TransferCreateParams.builder()
                .setAmount(amount)
                .setCurrency(currency)
                .setDestination(destinationAccount)
                // Lie le versement au paiement : Stripe ne verse que des fonds effectivement encaisses.
                .setSourceTransaction(sourceCharge)
                .setTransferGroup(transferGroup)
                .putMetadata("transactionId", transactionId)
                .build();
        return call(() -> Transfer.create(params, idempotent("transfer-transaction-" + transactionId)).getId());
    }

    @Override
    public String createExpressAccount(String sub, String email, String country) {
        AccountCreateParams params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry(country)
                .setEmail(email)
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                .setRequested(true)
                                .build())
                        .build())
                .putMetadata("sub", sub)
                .build();
        // Deux clics simultanes sur "configurer mes versements" ne creent qu'un compte.
        return call(() -> Account.create(params, idempotent("connect-account-" + sub)).getId());
    }

    @Override
    public Optional<ConnectedAccount> account(String accountId) {
        try {
            Account account = Account.retrieve(accountId);
            Account.Capabilities capabilities = account.getCapabilities();
            return Optional.of(new ConnectedAccount(
                    account.getId(),
                    Boolean.TRUE.equals(account.getDetailsSubmitted()),
                    Boolean.TRUE.equals(account.getPayoutsEnabled()),
                    capabilities != null && "active".equals(capabilities.getTransfers())));
        } catch (InvalidRequestException missing) {
            // Identifiant inconnu de Stripe : un acct_ saisi a la main du temps ou le profil
            // l'acceptait, ou un compte supprime.
            return Optional.empty();
        } catch (StripeException ex) {
            throw new StripeUnavailableException(ex);
        }
    }

    @Override
    public String onboardingLink(String accountId, String refreshUrl, String returnUrl) {
        AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build();
        return call(() -> AccountLink.create(params).getUrl());
    }

    private static RequestOptions idempotent(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(StripeCall<T> call) {
        try {
            return call.run();
        } catch (InvalidRequestException ex) {
            // Requete refusee par Stripe (montant superieur au paiement, compte non eligible...) :
            // la rejouer a l'identique ne changera rien.
            throw new IllegalArgumentException("Stripe refused the request: " + ex.getMessage(), ex);
        } catch (StripeException ex) {
            throw new StripeUnavailableException(ex);
        }
    }
}

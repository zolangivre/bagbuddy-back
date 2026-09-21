package com.bagbuddy.stripeservice.service;

import com.bagbuddy.stripeservice.client.UserClient;
import com.bagbuddy.stripeservice.gateway.StripeGateway;
import com.bagbuddy.stripeservice.gateway.StripeGateway.ConnectedAccount;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Ce qui sort de l'argent de la plateforme : remboursements aux acheteurs, versements aux
 * voyageurs, et l'onboarding Connect qui rend un voyageur payable.
 *
 * Le modele est celui des "charges et versements separes" : l'acheteur paie la plateforme
 * (PaymentIntent), qui garde les fonds jusqu'a ce que la transaction se termine, puis verse au
 * voyageur sa part, commission deduite. Ce service n'en decide pas les montants : c'est
 * transactionservice qui applique la politique (commission, remboursement partiel) et demande
 * ici l'execution. Ce qui est garanti ici, c'est de ne jamais payer deux fois.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    public enum TransferStatus { PAID, AWAITING_ACCOUNT }

    public record TransferResult(TransferStatus status, String transferId) {
    }

    public record AccountStatus(boolean connected, boolean detailsSubmitted, boolean payoutsEnabled,
                                boolean transfersActive) {
        static final AccountStatus NONE = new AccountStatus(false, false, false, false);
    }

    private final StripeGateway stripe;
    private final UserClient userClient;
    private final MeterRegistry meters;
    private final String currency;
    private final String country;
    private final String frontUrl;
    private final String returnPath;
    private final String refreshPath;

    public PayoutService(StripeGateway stripe,
                         UserClient userClient,
                         MeterRegistry meters,
                         @Value("${bagbuddy.stripe.currency:eur}") String currency,
                         @Value("${bagbuddy.stripe.connect.country:FR}") String country,
                         @Value("${bagbuddy.front-url:http://localhost:4200}") String frontUrl,
                         @Value("${bagbuddy.stripe.connect.return-path:/account?payouts=done}") String returnPath,
                         @Value("${bagbuddy.stripe.connect.refresh-path:/account?payouts=retry}") String refreshPath) {
        this.stripe = stripe;
        this.userClient = userClient;
        this.meters = meters;
        this.currency = currency;
        this.country = country;
        this.frontUrl = frontUrl.endsWith("/") ? frontUrl.substring(0, frontUrl.length() - 1) : frontUrl;
        this.returnPath = returnPath;
        this.refreshPath = refreshPath;
    }

    static String transferGroup(Long transactionId) {
        return "transaction-" + transactionId;
    }

    /**
     * Rembourse l'acheteur d'une transaction. Rejouable : un remboursement deja emis pour cette
     * transaction est renvoye tel quel, jamais double.
     */
    public String refund(Long transactionId, String paymentIntentId, long amount) {
        requirePositive(transactionId, paymentIntentId, amount);
        String key = String.valueOf(transactionId);
        Optional<String> existing = stripe.findRefund(paymentIntentId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String refundId = stripe.refund(paymentIntentId, amount, key);
        meters.counter("bagbuddy.payments.refunds").increment();
        log.info("Refunded {} (minor units) on PaymentIntent {} for transaction {}: {}",
                amount, paymentIntentId, transactionId, refundId);
        return refundId;
    }

    /**
     * Verse au voyageur sa part d'une transaction. Sans compte Connect pret a recevoir, rien n'est
     * verse et la reponse le dit : transactionservice garde le versement en attente et le
     * redemandera, l'argent restant sur la plateforme d'ici la.
     */
    public TransferResult transfer(Long transactionId, String paymentIntentId, String sellerSub, long amount) {
        requirePositive(transactionId, paymentIntentId, amount);
        if (sellerSub == null || sellerSub.isBlank()) {
            throw new IllegalArgumentException("sellerSub is required");
        }
        // La garde d'idempotence passe avant tout le reste : qu'un versement ait deja ete emis
        // pour cette transaction ne doit dependre de la reponse d'aucun autre service.
        String group = transferGroup(transactionId);
        Optional<String> existing = stripe.findTransfer(group);
        if (existing.isPresent()) {
            return new TransferResult(TransferStatus.PAID, existing.get());
        }

        String accountId = userClient.payoutAccount(sellerSub);
        Optional<ConnectedAccount> account = accountId == null ? Optional.empty() : stripe.account(accountId);
        if (account.isEmpty() || !account.get().transfersActive()) {
            return new TransferResult(TransferStatus.AWAITING_ACCOUNT, null);
        }

        String charge = stripe.latestChargeOf(paymentIntentId);
        String transferId = stripe.transfer(accountId, amount, currency, charge, group, String.valueOf(transactionId));
        meters.counter("bagbuddy.payments.transfers").increment();
        log.info("Transferred {} (minor units) to {} for transaction {}: {}",
                amount, accountId, transactionId, transferId);
        return new TransferResult(TransferStatus.PAID, transferId);
    }

    /**
     * Lien d'onboarding Connect du membre appelant. Le compte Express est cree au premier appel
     * et enregistre dans son profil ; les appels suivants reprennent le meme compte, la ou le
     * membre s'etait arrete.
     */
    public String startOnboarding(Jwt caller) {
        String sub = caller.getSubject();
        String accountId = userClient.payoutAccount(sub);
        if (accountId == null || stripe.account(accountId).isEmpty()) {
            accountId = stripe.createExpressAccount(sub, caller.getClaimAsString("email"), country);
            userClient.savePayoutAccount(sub, accountId);
        }
        return stripe.onboardingLink(accountId, frontUrl + refreshPath, frontUrl + returnPath);
    }

    public AccountStatus accountStatus(Jwt caller) {
        String accountId = userClient.payoutAccount(caller.getSubject());
        if (accountId == null) {
            return AccountStatus.NONE;
        }
        return stripe.account(accountId)
                .map(a -> new AccountStatus(true, a.detailsSubmitted(), a.payoutsEnabled(), a.transfersActive()))
                .orElse(AccountStatus.NONE);
    }

    private static void requirePositive(Long transactionId, String paymentIntentId, long amount) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
    }
}

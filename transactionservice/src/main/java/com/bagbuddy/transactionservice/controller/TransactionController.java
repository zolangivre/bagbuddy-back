package com.bagbuddy.transactionservice.controller;

import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    @Autowired
    private TransactionService transactionService;

    /**
     * Was "every transaction in the database". A transaction embeds both parties' email and
     * phone, so the listing is now scoped to the caller.
     */
    @GetMapping
    public List<Transaction> getMine(@AuthenticationPrincipal Jwt jwt) {
        return transactionService.getByUser(CallerIdentity.subOf(jwt));
    }

    @GetMapping("/seller/{sellerId}")
    public List<Transaction> getBySeller(@PathVariable String sellerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(sellerId, jwt);
        return transactionService.getBySeller(sellerId);
    }

    @GetMapping("/buyer/{buyerId}")
    public List<Transaction> getByBuyer(@PathVariable String buyerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(buyerId, jwt);
        return transactionService.getByBuyer(buyerId);
    }

    @GetMapping("/user/{userId}")
    public List<Transaction> getByUser(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(userId, jwt);
        return transactionService.getByUser(userId);
    }

    @GetMapping("/user/{userId}/count")
    public Long countByUser(@PathVariable String userId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(userId, jwt);
        return transactionService.countByUser(userId);
    }

    @GetMapping("/seller/{sellerId}/total-earned")
    public Double getTotalEarnedBySeller(@PathVariable String sellerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(sellerId, jwt);
        return transactionService.getTotalEarnedBySeller(sellerId);
    }

    @GetMapping("/buyer/{buyerId}/total-spent")
    public Double getTotalSpentByBuyer(@PathVariable String buyerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(buyerId, jwt);
        return transactionService.getTotalSpentByBuyer(buyerId);
    }

    @GetMapping("/{id}")
    public Transaction getOne(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return transactionService.getOne(id, CallerIdentity.subOf(jwt));
    }

    @PostMapping
    public Transaction create(@RequestBody Transaction tx, @AuthenticationPrincipal Jwt jwt) {
        return transactionService.create(tx, jwt);
    }

    @PutMapping("/{id}")
    public Transaction update(@PathVariable Long id,
                              @RequestBody Transaction body,
                              @AuthenticationPrincipal Jwt jwt) {
        return transactionService.update(id, body, CallerIdentity.subOf(jwt));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        transactionService.delete(id, CallerIdentity.subOf(jwt));
    }

    /**
     * Records a confirmed Stripe payment. Only reachable with the 'service' realm role
     * (see SecurityConfig): the caller is stripeservice acting on a signed webhook, never
     * a browser. This is the only path that may write the payment fields.
     */
    @PostMapping("/internal/{id}/payment")
    public Transaction confirmPayment(@PathVariable Long id, @RequestBody PaymentConfirmation body) {
        return transactionService.markPaid(id, body.paymentIntentId(), body.amount(), body.currency());
    }

    public record PaymentConfirmation(String paymentIntentId, Long amount, String currency) {
    }

    private void requireSelf(String userId, Jwt jwt) {
        if (!CallerIdentity.subOf(jwt).equals(userId)) {
            throw new AccessDeniedException("Callers may only read their own transactions");
        }
    }
}

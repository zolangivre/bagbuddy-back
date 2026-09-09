package com.bagbuddy.transactionservice.controller;

import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.service.TransactionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Enregistre un paiement confirme par Stripe. Volontairement reste en REST : l'appelant est
 * stripeservice agissant sur un webhook signe, jamais un navigateur, et le role realm 'service'
 * garde la route (voir SecurityConfig). C'est le seul chemin qui ecrit les colonnes de paiement.
 */
@RestController
@RequestMapping("/transactions/internal")
public class TransactionInternalController {

    private final TransactionService transactionService;

    public TransactionInternalController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/{id}/payment")
    public Transaction confirmPayment(@PathVariable Long id, @RequestBody PaymentConfirmation body) {
        return transactionService.markPaid(id, body.paymentIntentId(), body.amount(), body.currency());
    }

    public record PaymentConfirmation(String paymentIntentId, Long amount, String currency) {
    }
}

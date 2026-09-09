package com.bagbuddy.transactionservice.controller;

import com.bagbuddy.transactionservice.dto.CreateTransactionInput;
import com.bagbuddy.transactionservice.dto.TransactionView;
import com.bagbuddy.transactionservice.dto.UpdateTransactionInput;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Surface publique de transactionservice. Une transaction porte les coordonnees des deux
 * parties : chaque lecture est donc soit cadree sur l'appelant, soit filtree par
 * TransactionService, jamais ouverte.
 */
@Controller
public class TransactionGraphQlController {

    private final TransactionService transactionService;

    public TransactionGraphQlController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @QueryMapping
    public List<TransactionView> myTransactions(@AuthenticationPrincipal Jwt jwt) {
        return TransactionView.of(transactionService.getByUser(CallerIdentity.subOf(jwt)));
    }

    @QueryMapping
    public TransactionView transaction(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        return TransactionView.of(transactionService.getOne(id, CallerIdentity.subOf(jwt)));
    }

    @QueryMapping
    public List<TransactionView> transactionsBySeller(@Argument String sellerId,
                                                      @AuthenticationPrincipal Jwt jwt) {
        requireSelf(sellerId, jwt);
        return TransactionView.of(transactionService.getBySeller(sellerId));
    }

    @QueryMapping
    public List<TransactionView> transactionsByBuyer(@Argument String buyerId,
                                                     @AuthenticationPrincipal Jwt jwt) {
        requireSelf(buyerId, jwt);
        return TransactionView.of(transactionService.getByBuyer(buyerId));
    }

    @QueryMapping
    public List<TransactionView> transactionsByUser(@Argument String userId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        requireSelf(userId, jwt);
        return TransactionView.of(transactionService.getByUser(userId));
    }

    @QueryMapping
    public Long transactionCount(@Argument String userId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(userId, jwt);
        return transactionService.countByUser(userId);
    }

    @QueryMapping
    public Double totalEarned(@Argument String sellerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(sellerId, jwt);
        return transactionService.getTotalEarnedBySeller(sellerId);
    }

    @QueryMapping
    public Double totalSpent(@Argument String buyerId, @AuthenticationPrincipal Jwt jwt) {
        requireSelf(buyerId, jwt);
        return transactionService.getTotalSpentByBuyer(buyerId);
    }

    @MutationMapping
    public TransactionView createTransaction(@Argument CreateTransactionInput input,
                                             @AuthenticationPrincipal Jwt jwt) {
        return TransactionView.of(transactionService.create(input.toTransaction(), jwt));
    }

    @MutationMapping
    public TransactionView updateTransaction(@Argument Long id,
                                             @Argument UpdateTransactionInput input,
                                             @AuthenticationPrincipal Jwt jwt) {
        return TransactionView.of(
                transactionService.update(id, input.toTransaction(), CallerIdentity.subOf(jwt)));
    }

    @MutationMapping
    public boolean deleteTransaction(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        transactionService.delete(id, CallerIdentity.subOf(jwt));
        return true;
    }

    private void requireSelf(String userId, Jwt jwt) {
        if (!CallerIdentity.subOf(jwt).equals(userId)) {
            throw new AccessDeniedException("Callers may only read their own transactions");
        }
    }
}

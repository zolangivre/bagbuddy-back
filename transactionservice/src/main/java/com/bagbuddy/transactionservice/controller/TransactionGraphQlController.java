package com.bagbuddy.transactionservice.controller;

import com.bagbuddy.transactionservice.dto.CreateTransactionInput;
import com.bagbuddy.transactionservice.dto.TransactionMessageView;
import com.bagbuddy.transactionservice.dto.TransactionView;
import com.bagbuddy.transactionservice.dto.UpdateTransactionInput;
import com.bagbuddy.transactionservice.security.CallerIdentity;
import com.bagbuddy.transactionservice.service.TransactionMessageService;
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
    private final TransactionMessageService messageService;

    public TransactionGraphQlController(TransactionService transactionService,
                                        TransactionMessageService messageService) {
        this.transactionService = transactionService;
        this.messageService = messageService;
    }

    @QueryMapping
    public List<TransactionView> myTransactions(@AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionView.of(transactionService.getByUser(caller), caller);
    }

    @QueryMapping
    public TransactionView transaction(@Argument Long id, @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionView.of(transactionService.getOne(id, caller), caller);
    }

    @QueryMapping
    public List<TransactionView> transactionsBySeller(@Argument String sellerId,
                                                      @AuthenticationPrincipal Jwt jwt) {
        requireSelf(sellerId, jwt);
        return TransactionView.of(transactionService.getBySeller(sellerId), sellerId);
    }

    @QueryMapping
    public List<TransactionView> transactionsByBuyer(@Argument String buyerId,
                                                     @AuthenticationPrincipal Jwt jwt) {
        requireSelf(buyerId, jwt);
        return TransactionView.of(transactionService.getByBuyer(buyerId), buyerId);
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
        return TransactionView.of(transactionService.create(input.toTransaction(), jwt), CallerIdentity.subOf(jwt));
    }

    @MutationMapping
    public TransactionView updateTransaction(@Argument Long id,
                                             @Argument UpdateTransactionInput input,
                                             @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionView.of(transactionService.update(id, input.toTransaction(), caller), caller);
    }

    /** Le voyageur clot la transaction avec le code que le destinataire lui a donne. */
    @MutationMapping
    public TransactionView confirmHandover(@Argument Long id, @Argument String code,
                                           @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionView.of(transactionService.confirmHandover(id, code, caller), caller);
    }

    @QueryMapping
    public List<TransactionMessageView> transactionMessages(@Argument Long transactionId,
                                                           @Argument Long afterId,
                                                           @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionMessageView.of(messageService.list(transactionId, afterId, caller), caller);
    }

    @MutationMapping
    public TransactionMessageView sendTransactionMessage(@Argument Long transactionId,
                                                         @Argument String body,
                                                         @AuthenticationPrincipal Jwt jwt) {
        String caller = CallerIdentity.subOf(jwt);
        return TransactionMessageView.of(messageService.send(transactionId, body, caller), caller);
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

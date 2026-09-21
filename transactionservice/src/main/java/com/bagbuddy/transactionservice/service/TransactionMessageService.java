package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.TransactionMessage;
import com.bagbuddy.transactionservice.repository.TransactionMessageRepository;
import com.bagbuddy.transactionservice.web.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Messagerie d'une transaction : de quoi convenir du lieu et de l'heure de la remise sans
 * s'echanger son numero. Lue et ecrite par les deux parties seulement.
 *
 * Pas de push : le front relit le fil a intervalle, avec afterId pour ne recevoir que la suite.
 * Une transaction annulee garde son fil lisible mais n'en accepte plus.
 */
@Service
public class TransactionMessageService {

    static final int MAX_LENGTH = 2000;
    /** Un fil ne se relit jamais d'une traite au-dela : afterId pagine. */
    static final int PAGE = 200;
    /** Anti-rafale, par auteur et par transaction. */
    static final int MAX_PER_MINUTE = 20;

    private final TransactionService transactions;
    private final TransactionMessageRepository repository;
    private final TransactionStateMachine stateMachine;

    public TransactionMessageService(TransactionService transactions, TransactionMessageRepository repository,
                                     TransactionStateMachine stateMachine) {
        this.transactions = transactions;
        this.repository = repository;
        this.stateMachine = stateMachine;
    }

    @Transactional(readOnly = true)
    public List<TransactionMessage> list(Long transactionId, Long afterId, String callerSub) {
        transactions.getOne(transactionId, callerSub);
        return repository.findByTransactionIdAndIdGreaterThanOrderByIdAsc(transactionId,
                afterId == null ? 0L : afterId, PageRequest.of(0, PAGE));
    }

    @Transactional
    public TransactionMessage send(Long transactionId, String body, String callerSub) {
        Transaction tx = transactions.getOne(transactionId, callerSub);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new BusinessException("invalid_message", "A message has 1 to " + MAX_LENGTH + " characters.");
        }
        if (!stateMachine.acceptsMessages(TransactionStateMachine.StatusPair.of(tx))) {
            throw new BusinessException("conversation_closed", "This transaction was cancelled.");
        }
        if (repository.countByTransactionIdAndSenderSubAndCreatedAtAfter(transactionId, callerSub,
                LocalDateTime.now().minusMinutes(1)) >= MAX_PER_MINUTE) {
            throw new BusinessException("too_many_messages", "Too many messages in a minute.");
        }
        TransactionMessage message = new TransactionMessage();
        message.setTransactionId(transactionId);
        message.setSenderSub(callerSub);
        message.setBody(text);
        return repository.save(message);
    }
}

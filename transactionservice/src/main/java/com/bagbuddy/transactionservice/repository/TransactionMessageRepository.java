package com.bagbuddy.transactionservice.repository;

import com.bagbuddy.transactionservice.model.TransactionMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TransactionMessageRepository extends JpaRepository<TransactionMessage, Long> {

    /** Le fil dans l'ordre, a partir d'un id exclu : 0 pour tout relire. */
    List<TransactionMessage> findByTransactionIdAndIdGreaterThanOrderByIdAsc(Long transactionId, Long afterId,
                                                                            Pageable page);

    long countByTransactionIdAndSenderSubAndCreatedAtAfter(Long transactionId, String senderSub,
                                                           LocalDateTime after);
}

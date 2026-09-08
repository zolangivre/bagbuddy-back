package com.bagbuddy.transactionservice.repository;

import com.bagbuddy.transactionservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByBuyerId(String buyerId);

    List<Transaction> findBySellerId(String sellerId);

    List<Transaction> findAllByOrderByCreatedAtDesc();

    List<Transaction> findByBuyerIdOrSellerIdOrderByCreatedAtDesc(String buyerId, String sellerId);

    Long countByBuyerIdOrSellerId(String buyerId, String sellerId);
}

package com.bagbuddy.transactionservice.service;

import com.bagbuddy.transactionservice.model.ListingInfo;
import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.model.UserInfo;
import com.bagbuddy.transactionservice.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    public List<Transaction> getAll() {
        return transactionRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Transaction> getBySeller(String sellerId) {
        return transactionRepository.findBySellerId(sellerId);
    }

    public List<Transaction> getByBuyer(String buyerId) {
        return transactionRepository.findByBuyerId(buyerId);
    }

    public List<Transaction> getByUser(String userId) {
        return transactionRepository.findByBuyerIdOrSellerIdOrderByCreatedAtDesc(userId, userId);
    }

    public Long countByUser(String userId) {
        return transactionRepository.countByBuyerIdOrSellerId(userId, userId);
    }

    public Double getTotalEarnedBySeller(String sellerId) {
        return transactionRepository.findBySellerId(sellerId)
                .stream()
                .filter(tx -> "completed".equalsIgnoreCase(tx.getBuyerStatus()) &&
                        "completed".equalsIgnoreCase(tx.getSellerStatus()))
                .mapToDouble(tx -> tx.getTotal().doubleValue())
                .sum();
    }

    public Double getTotalSpentByBuyer(String buyerId) {
        return transactionRepository.findByBuyerId(buyerId)
                .stream()
                .filter(tx -> "completed".equalsIgnoreCase(tx.getBuyerStatus()) &&
                        "completed".equalsIgnoreCase(tx.getSellerStatus()))
                .mapToDouble(tx -> tx.getTotal().doubleValue())
                .sum();
    }

    public Transaction getOne(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id " + id));
    }

    @Transactional
    public Transaction create(Transaction tx) {
        if (tx.getListingInfo() == null) {
            tx.setListingInfo(new ListingInfo());
        }
        if (tx.getListingInfo().getSellerUserInfo() == null) {
            tx.getListingInfo().setSellerUserInfo(new UserInfo());
        }
        if (tx.getListingInfo().getCreatedAt() == null) {
            tx.getListingInfo().setCreatedAt(LocalDateTime.now().toString());
        }
        return transactionRepository.save(tx);
    }

    @Transactional
    public Transaction update(Long id, Transaction body) {
        Transaction existing = getOne(id);

        existing.setSellerStatus(body.getSellerStatus());
        existing.setBuyerStatus(body.getBuyerStatus());
        existing.setWeight(body.getWeight());
        existing.setTotal(body.getTotal());
        existing.setListingInfo(body.getListingInfo());
        existing.setBuyerReview(body.getBuyerReview());
        existing.setSellerReview(body.getSellerReview());

        if (body.getStripePaymentIntentId() != null) {
            existing.setStripePaymentIntentId(body.getStripePaymentIntentId());
        }
        if (body.getStripeCurrency() != null) {
            existing.setStripeCurrency(body.getStripeCurrency());
        }
        if (body.getStripeAmount() != null) {
            existing.setStripeAmount(body.getStripeAmount());
        }
        if (body.getPaidAt() != null) {
            existing.setPaidAt(body.getPaidAt());
        }

        return transactionRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        if (!transactionRepository.existsById(id)) {
            throw new RuntimeException("Transaction not found with id " + id);
        }
        transactionRepository.deleteById(id);
    }
}
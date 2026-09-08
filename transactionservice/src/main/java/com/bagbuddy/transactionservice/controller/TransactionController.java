package com.bagbuddy.transactionservice.controller;

import com.bagbuddy.transactionservice.model.Transaction;
import com.bagbuddy.transactionservice.service.TransactionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    @Autowired
    private TransactionService transactionService;

    @GetMapping
    public List<Transaction> getAll() {
        return transactionService.getAll();
    }

    @GetMapping("/seller/{sellerId}")
    public List<Transaction> getBySeller(@PathVariable String sellerId) {
        return transactionService.getBySeller(sellerId);
    }

    @GetMapping("/buyer/{buyerId}")
    public List<Transaction> getByBuyer(@PathVariable String buyerId) {
        return transactionService.getByBuyer(buyerId);
    }

    @GetMapping("/user/{userId}")
    public List<Transaction> getByUser(@PathVariable String userId) {
        return transactionService.getByUser(userId);
    }

    @GetMapping("/user/{userId}/count")
    public Long countByUser(@PathVariable String userId) {
        return transactionService.countByUser(userId);
    }

    @GetMapping("/seller/{sellerId}/total-earned")
    public Double getTotalEarnedBySeller(@PathVariable String sellerId) {
        return transactionService.getTotalEarnedBySeller(sellerId);
    }

    @GetMapping("/buyer/{buyerId}/total-spent")
    public Double getTotalSpentByBuyer(@PathVariable String buyerId) {
        return transactionService.getTotalSpentByBuyer(buyerId);
    }

    @GetMapping("/{id}")
    public Transaction getOne(@PathVariable Long id) {
        return transactionService.getOne(id);
    }

    @PostMapping
    public Transaction create(@RequestBody Transaction tx) {
        return transactionService.create(tx);
    }

    @PutMapping("/{id}")
    public Transaction update(@PathVariable Long id, @RequestBody Transaction body) {
        return transactionService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        transactionService.delete(id);
    }
}
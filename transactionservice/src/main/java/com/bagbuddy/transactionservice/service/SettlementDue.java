package com.bagbuddy.transactionservice.service;

/** Une transaction a un remboursement ou un versement a executer, une fois son ecriture validee. */
public record SettlementDue(Long transactionId) {
}

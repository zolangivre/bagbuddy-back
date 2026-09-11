package com.bagbuddy.transactionservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Transaction emails leave the request thread (see TransactionNotifier). They run on Spring
 * Boot's application task executor, on virtual threads since spring.threads.virtual.enabled.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

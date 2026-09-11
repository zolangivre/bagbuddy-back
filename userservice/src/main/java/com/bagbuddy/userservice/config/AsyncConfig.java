package com.bagbuddy.userservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Lets a password reset request answer before the account lookup and the email are done
 * (see PasswordResetService.requestReset). Runs on Spring Boot's application task executor.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}

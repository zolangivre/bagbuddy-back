package com.bagbuddy.tripservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Alert emails leave the request thread (TripAlertNotifier), on the application task executor. */
@Configuration
@EnableAsync
public class AsyncConfig {
}

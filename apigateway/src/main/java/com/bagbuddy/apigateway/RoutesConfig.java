/* package com.woofwoof.apigateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class RoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> stayServiceRoutes() {
        return GatewayRouterFunctions.route("stay-service")
                .GET("/stays/**", http())
                .before(uri("lb://stay-service"))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> bookingServiceRoutes() {
        return GatewayRouterFunctions.route("booking-service")
                .GET("/bookings/**", http())   // Récupérer toutes les réservations
                .POST("/bookings/**", http())  // Créer une réservation
                .before(uri("lb://booking-service"))
                .build();
    }
}
*/
package com.opentraum.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // Auth Service
                .route("auth-service", r -> r
                        .path("/api/v1/auth/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Routed", "true"))
                        .uri("http://auth-service:8081"))

                // User Service
                .route("user-service", r -> r
                        .path("/api/v1/users/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Routed", "true"))
                        .uri("http://user-service:8082"))

                // Event Service
                .route("event-service", r -> r
                        .path("/api/v1/events/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Routed", "true"))
                        .uri("http://event-service:8083"))

                // Reservation Service
                .route("reservation-service", r -> r
                        .path("/api/v1/reservations/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Routed", "true"))
                        .uri("http://reservation-service:8084"))

                // Payment Service
                .route("payment-service", r -> r
                        .path("/api/v1/payments/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Gateway-Routed", "true"))
                        .uri("http://payment-service:8085"))

                .build();
    }
}

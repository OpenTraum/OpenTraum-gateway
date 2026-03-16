package com.opentraum.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that resolves the tenant ID for multi-tenancy support.
 *
 * Tenant resolution strategy (priority order):
 * 1. X-Tenant-Id request header
 * 2. Subdomain extraction (e.g., tenant1.opentraum.com -> tenant1)
 */
@Component
public class TenantResolverFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantResolverFilter.class);

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String DEFAULT_TENANT = "default";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String tenantId = resolveTenantId(request);

        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Tenant ID could not be resolved for request: {}", request.getURI());
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        log.debug("Resolved tenant ID: {} for request: {}", tenantId, request.getURI().getPath());

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(TENANT_HEADER, tenantId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private String resolveTenantId(ServerHttpRequest request) {
        // 1. Check X-Tenant-Id header
        String headerTenant = request.getHeaders().getFirst(TENANT_HEADER);
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant.trim();
        }

        // 2. Extract from subdomain
        String host = request.getHeaders().getFirst("Host");
        if (host != null) {
            String subdomain = extractSubdomain(host);
            if (subdomain != null) {
                return subdomain;
            }
        }

        // 3. Fallback to default tenant
        return DEFAULT_TENANT;
    }

    private String extractSubdomain(String host) {
        // Remove port if present
        String hostname = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        // Skip localhost and IP addresses
        if (hostname.equals("localhost") || hostname.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return null;
        }

        String[] parts = hostname.split("\\.");
        // At least 3 parts needed (subdomain.domain.tld)
        if (parts.length >= 3) {
            String subdomain = parts[0];
            // Skip common non-tenant subdomains
            if (!subdomain.equals("www") && !subdomain.equals("api")) {
                return subdomain;
            }
        }

        return null;
    }

    @Override
    public int getOrder() {
        // Run early in the filter chain to ensure tenant is available for downstream filters
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}

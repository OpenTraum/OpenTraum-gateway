package com.opentraum.gateway.filter;

import com.opentraum.gateway.util.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 인증 GlobalFilter.
 * 1. Authorization 헤더에서 Bearer 토큰 추출
 * 2. 토큰 유효성 검증 + Redis 블랙리스트 확인
 * 3. X-User-Id, X-User-Role 헤더 주입
 * 4. 공개 엔드포인트는 인증 없이 통과
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtProvider jwtProvider;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BLACKLIST_PREFIX = "blacklist:";

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    // ORGANIZER 역할 필수 경로
    private static final List<String> ORGANIZER_ONLY_PATTERNS = List.of(
            "/api/v1/admin/**"
    );

    // 인증 불필요 경로 (method + pattern)
    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
            new PublicEndpoint(null, "/api/v1/auth/**"),
            new PublicEndpoint(HttpMethod.GET, "/api/v1/concerts/**"),
            new PublicEndpoint(HttpMethod.GET, "/api/v1/schedules/**"),
            new PublicEndpoint(null, "/api/v1/payment/webhook"),
            new PublicEndpoint(null, "/actuator/**"),
            new PublicEndpoint(null, "/swagger-ui/**"),
            new PublicEndpoint(null, "/swagger-ui.html"),
            new PublicEndpoint(null, "/v3/api-docs/**"),
            new PublicEndpoint(null, "/webjars/**")
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 내부 API 차단 (서비스 간 직접 호출만 허용)
        if (path.contains("/internal/")) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // 공개 엔드포인트 확인
        if (isPublicEndpoint(request)) {
            // 토큰이 있으면 파싱해서 헤더에 추가 (선택적 인증)
            String token = resolveToken(request);
            if (token != null && jwtProvider.validateToken(token)) {
                return injectUserHeaders(exchange, chain, token);
            }
            return chain.filter(exchange);
        }

        // 인증 필요 경로
        String token = resolveToken(request);
        if (token == null) {
            log.debug("인증 토큰 없음: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        if (!jwtProvider.validateToken(token)) {
            log.debug("유효하지 않은 토큰: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Redis 블랙리스트 확인
        String blacklistKey = BLACKLIST_PREFIX + token;
        return redisTemplate.hasKey(blacklistKey)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        log.debug("블랙리스트 토큰: {}", path);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }
                    return injectUserHeaders(exchange, chain, token);
                });
    }

    private Mono<Void> injectUserHeaders(ServerWebExchange exchange, GatewayFilterChain chain, String token) {
        Long userId = jwtProvider.getUserId(token);
        String role = jwtProvider.getRole(token);
        String tenantId = jwtProvider.getTenantId(token);
        String path = exchange.getRequest().getURI().getPath();

        // admin 경로 역할 검증
        if (isOrganizerOnly(path) && !"ORGANIZER".equals(role)) {
            log.debug("권한 부족: userId={}, role={}, path={}", userId, role, path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        var requestBuilder = exchange.getRequest().mutate()
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role != null ? role : "CONSUMER");

        if (tenantId != null) {
            requestBuilder.header("X-Tenant-Id", tenantId);
        }

        ServerHttpRequest mutatedRequest = requestBuilder.build();

        log.debug("JWT 인증 완료: userId={}, role={}, tenantId={}, path={}", userId, role, tenantId, path);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isOrganizerOnly(String path) {
        return ORGANIZER_ONLY_PATTERNS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        return PUBLIC_ENDPOINTS.stream().anyMatch(ep -> {
            boolean pathMatches = pathMatcher.match(ep.pattern(), path);
            boolean methodMatches = ep.method() == null || ep.method().equals(method);
            return pathMatches && methodMatches;
        });
    }

    private String resolveToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    @Override
    public int getOrder() {
        // TenantResolverFilter(+10) 이후, 라우팅 이전에 실행
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private record PublicEndpoint(HttpMethod method, String pattern) {}
}

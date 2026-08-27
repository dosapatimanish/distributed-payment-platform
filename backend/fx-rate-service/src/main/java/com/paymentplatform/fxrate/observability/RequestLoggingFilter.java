package com.paymentplatform.fxrate.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Logs one INFO line when an HTTP request arrives and one when the response leaves - method,
 * path, query string, response status, elapsed time and client IP. For the duration of the
 * request it also stamps a short random id into the SLF4J {@link MDC} under {@code reqId}, which
 * the log pattern prints on every line, so all logging produced while handling a single request
 * can be correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_KEY = "reqId";

    private static final Logger log = LoggerFactory.getLogger("com.paymentplatform.fxrate.http");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(REQUEST_ID_KEY, requestId);

        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        long startNanos = System.nanoTime();

        log.info("--> {} {}{} from {}", request.getMethod(), request.getRequestURI(), query, clientIp(request));
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("<-- {} {}{} {} [{} ms]", request.getMethod(), request.getRequestURI(), query,
                    response.getStatus(), elapsedMs);
            MDC.remove(REQUEST_ID_KEY);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

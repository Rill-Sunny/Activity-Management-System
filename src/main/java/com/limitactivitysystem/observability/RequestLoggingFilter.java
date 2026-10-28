package com.limitactivitysystem.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Generate on the server so arbitrary client headers cannot inject log content.
        String requestId = UUID.randomUUID().toString();
        String previousRequestId = MDC.get("requestId");
        long started = System.nanoTime();
        boolean failed = false;
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                int status = failed && !response.isCommitted() ? 500 : response.getStatus();
                // Log only metadata. Never log query strings, headers, bodies, or exceptions here.
                log.info("HTTP request method={} path={} status={} durationMs={}",
                        safeLogValue(request.getMethod()), safeLogValue(request.getRequestURI()), status,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("requestId");
                } else {
                    MDC.put("requestId", previousRequestId);
                }
            }
        }
    }

    private static String safeLogValue(String value) {
        // Keep one request on one log line, including when a proxy supplies unusual URI characters.
        return value.replaceAll("[\\p{Cntrl}\\s]", "_");
    }
}

package com.abel.ledger.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a correlation id for every request and makes it available
 * three ways: echoed back in the {@value #CORRELATION_ID_HEADER} response
 * header (so a client or upstream proxy can tie its own logs to this
 * service's), placed in the SLF4J {@link MDC} under {@value #MDC_KEY} (so
 * every log line emitted while handling this request carries it,
 * regardless of which class does the logging), and therefore also visible
 * to {@code @TransactionalEventListener(AFTER_COMMIT)} handlers such as
 * {@code LedgerEventPublisher}, since those run synchronously on this same
 * request thread before the MDC entry is cleared in the {@code finally}
 * block below.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the correlation id is
 * established before any other filter or the request logging it enables.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = Uuid7Generator.generate().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

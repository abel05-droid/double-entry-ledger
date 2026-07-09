package com.abel.ledger.observability;

import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.InvalidPostingRequestException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.logstash.logback.argument.StructuredArguments;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adds structured logging and Micrometer metrics around
 * {@code PostingService}'s posting and concurrency-recovery paths, without
 * any change to that class: every join point here is a plain method-call
 * boundary on an already-Spring-proxied bean, so this aspect is applied by
 * composing an additional proxy advice, not by editing
 * {@code PostingService}'s source. This is a deliberate choice for exactly
 * the classes Phase 6 says not to modify — zero diff to
 * {@code PostingService.java} is the strongest possible guarantee that no
 * behavior changed.
 *
 * <p>See docs/architecture.md, "Observability", for the full metrics list
 * and what each one answers operationally.
 */
@Aspect
@Component
public class PostingObservabilityAspect {

    private static final Logger log = LoggerFactory.getLogger("com.abel.ledger.observability.posting");

    private final MeterRegistry meterRegistry;

    public PostingObservabilityAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Wraps the single public entry point into the posting engine. This is
     * where {@code ledger.posting.requests} and {@code ledger.posting.duration}
     * are recorded — covering the full outcome of a client's request,
     * whether it succeeded directly, succeeded via a same-request replay,
     * succeeded via {@link #aroundConcurrencyRecovery concurrency-race
     * recovery}, or failed for any reason.
     */
    @Around("execution(* com.abel.ledger.service.PostingService.post(..))")
    public Object aroundPost(ProceedingJoinPoint joinPoint) throws Throwable {
        PostingRequest request = (PostingRequest) joinPoint.getArgs()[0];
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = joinPoint.proceed();
            sample.stop(meterRegistry.timer("ledger.posting.duration", "outcome", "success"));
            meterRegistry.counter("ledger.posting.requests", "outcome", "success").increment();

            PostingResult postingResult = (PostingResult) result;
            log.info(
                    "Journal entry posted {} {} {}",
                    StructuredArguments.kv("journalEntryId", postingResult.journalEntryId()),
                    StructuredArguments.kv("idempotencyKey", request.idempotencyKey()),
                    StructuredArguments.kv("referenceId", postingResult.referenceId()));
            return result;
        } catch (Exception ex) {
            String failureReason = classifyFailure(ex);
            sample.stop(meterRegistry.timer("ledger.posting.duration", "outcome", "failure"));
            meterRegistry
                    .counter("ledger.posting.requests", "outcome", "failure", "failure_reason", failureReason)
                    .increment();
            if (ex instanceof IdempotencyKeyConflictException) {
                meterRegistry.counter("ledger.idempotency.conflicts").increment();
            }

            log.warn(
                    "Journal entry posting failed {} {} {} {} {}",
                    StructuredArguments.kv("idempotencyKey", request.idempotencyKey()),
                    StructuredArguments.kv("referenceId", request.referenceId()),
                    StructuredArguments.kv("failureReason", failureReason),
                    StructuredArguments.kv("exceptionClass", ex.getClass().getName()),
                    StructuredArguments.kv("exceptionMessage", ex.getMessage()));
            throw ex;
        }
    }

    /**
     * Wraps {@code recoverFromConcurrentIdempotencyKeyInsert}, which
     * {@code PostingService.post()} only calls after catching a
     * {@code DataIntegrityViolationException} from a losing concurrency
     * race — so every invocation of this join point is, by construction, a
     * detected race. {@code ledger.concurrency.recoveries} counts races
     * detected, independent of whether the recovery itself then succeeds
     * (replays the winner) or fails (rethrows a genuinely unrelated
     * conflict) — that success/failure is still separately captured by
     * {@link #aroundPost} around the whole request.
     */
    @Around("execution(* com.abel.ledger.service.PostingService.recoverFromConcurrentIdempotencyKeyInsert(..))")
    public Object aroundConcurrencyRecovery(ProceedingJoinPoint joinPoint) throws Throwable {
        PostingRequest request = (PostingRequest) joinPoint.getArgs()[0];
        meterRegistry.counter("ledger.concurrency.recoveries").increment();

        try {
            Object result = joinPoint.proceed();
            PostingResult postingResult = (PostingResult) result;
            log.info(
                    "Recovered from concurrent idempotency-key race {} {}",
                    StructuredArguments.kv("journalEntryId", postingResult.journalEntryId()),
                    StructuredArguments.kv("idempotencyKey", request.idempotencyKey()));
            return result;
        } catch (Exception ex) {
            log.warn(
                    "Concurrency-race recovery did not resolve to a replay {} {} {}",
                    StructuredArguments.kv("idempotencyKey", request.idempotencyKey()),
                    StructuredArguments.kv("exceptionClass", ex.getClass().getName()),
                    StructuredArguments.kv("exceptionMessage", ex.getMessage()));
            throw ex;
        }
    }

    private String classifyFailure(Throwable ex) {
        if (ex instanceof UnbalancedJournalEntryException) {
            return "unbalanced";
        }
        if (ex instanceof CurrencyMismatchException) {
            return "currency_mismatch";
        }
        if (ex instanceof IdempotencyKeyConflictException) {
            return "idempotency_conflict";
        }
        if (ex instanceof AccountNotFoundException) {
            return "account_not_found";
        }
        if (ex instanceof InvalidPostingRequestException) {
            return "invalid_request";
        }
        return "unknown";
    }
}

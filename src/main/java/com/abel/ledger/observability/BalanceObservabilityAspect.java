package com.abel.ledger.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Adds {@code ledger.balance.requests} / {@code ledger.balance.duration}
 * metrics around {@code BalanceService.getBalance}, without modifying that
 * class — see {@link PostingObservabilityAspect} for why AOP is used here
 * instead of inline instrumentation.
 */
@Aspect
@Component
public class BalanceObservabilityAspect {

    private final MeterRegistry meterRegistry;

    public BalanceObservabilityAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("execution(* com.abel.ledger.service.BalanceService.getBalance(..))")
    public Object aroundGetBalance(ProceedingJoinPoint joinPoint) throws Throwable {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = joinPoint.proceed();
            sample.stop(meterRegistry.timer("ledger.balance.duration", "outcome", "success"));
            meterRegistry.counter("ledger.balance.requests", "outcome", "success").increment();
            return result;
        } catch (Exception ex) {
            sample.stop(meterRegistry.timer("ledger.balance.duration", "outcome", "failure"));
            meterRegistry.counter("ledger.balance.requests", "outcome", "failure").increment();
            throw ex;
        }
    }
}

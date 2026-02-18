package com.calorietracker.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.calorietracker.service.PerformanceMonitorService;

@Aspect
@Component
public class RepositoryTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(RepositoryTimingAspect.class);

    private final PerformanceMonitorService performanceMonitorService;
    private final long infoThresholdMs;
    private final long warnThresholdMs;

    public RepositoryTimingAspect(
        PerformanceMonitorService performanceMonitorService,
        @Value("${app.perf.repository.info-ms:50}") long infoThresholdMs,
        @Value("${app.perf.repository.warn-ms:180}") long warnThresholdMs
    ) {
        this.performanceMonitorService = performanceMonitorService;
        this.infoThresholdMs = infoThresholdMs;
        this.warnThresholdMs = warnThresholdMs;
    }

    @Around("execution(* com.calorietracker.repository..*(..))")
    public Object timeRepositoryCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String key = joinPoint.getSignature().toShortString();
            performanceMonitorService.recordRepositoryCall(key, durationMs);

            if (durationMs >= warnThresholdMs) {
                log.warn("slow-repository {} durationMs={}", key, durationMs);
            } else if (durationMs >= infoThresholdMs) {
                log.info("repository {} durationMs={}", key, durationMs);
            }
        }
    }
}

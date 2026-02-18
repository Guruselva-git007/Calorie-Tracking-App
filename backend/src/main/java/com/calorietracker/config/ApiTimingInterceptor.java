package com.calorietracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.calorietracker.service.PerformanceMonitorService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiTimingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiTimingInterceptor.class);
    private static final String START_NANOS = "_api_start_nanos";

    private final PerformanceMonitorService performanceMonitorService;
    private final long infoThresholdMs;
    private final long warnThresholdMs;

    public ApiTimingInterceptor(
        PerformanceMonitorService performanceMonitorService,
        @Value("${app.perf.api.info-ms:120}") long infoThresholdMs,
        @Value("${app.perf.api.warn-ms:400}") long warnThresholdMs
    ) {
        this.performanceMonitorService = performanceMonitorService;
        this.infoThresholdMs = infoThresholdMs;
        this.warnThresholdMs = warnThresholdMs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object startNanos = request.getAttribute(START_NANOS);
        if (!(startNanos instanceof Long start)) {
            return;
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String key = request.getMethod() + " " + request.getRequestURI();
        performanceMonitorService.recordApiCall(key, durationMs);
        response.setHeader("X-Response-Time-Ms", String.valueOf(durationMs));

        int status = response.getStatus();
        if (durationMs >= warnThresholdMs) {
            log.warn("slow-api {} status={} durationMs={}", key, status, durationMs);
        } else if (durationMs >= infoThresholdMs) {
            log.info("api {} status={} durationMs={}", key, status, durationMs);
        }
    }
}

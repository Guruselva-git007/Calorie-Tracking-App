package com.calorietracker.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.calorietracker.dto.PerformanceMetricResponse;
import com.calorietracker.dto.PerformanceSummaryResponse;

@Service
public class PerformanceMonitorService {

    private final ConcurrentMap<String, MetricAccumulator> apiMetrics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MetricAccumulator> repositoryMetrics = new ConcurrentHashMap<>();

    public void recordApiCall(String name, long durationMs) {
        apiMetrics.computeIfAbsent(name, ignored -> new MetricAccumulator()).record(durationMs);
    }

    public void recordRepositoryCall(String name, long durationMs) {
        repositoryMetrics.computeIfAbsent(name, ignored -> new MetricAccumulator()).record(durationMs);
    }

    public PerformanceSummaryResponse summary(int limit) {
        int max = limit <= 0 ? 12 : Math.min(limit, 200);

        PerformanceSummaryResponse response = new PerformanceSummaryResponse();
        response.setGeneratedAt(Instant.now());
        response.setApiMetrics(topMetrics(apiMetrics, max));
        response.setRepositoryMetrics(topMetrics(repositoryMetrics, max));
        return response;
    }

    private List<PerformanceMetricResponse> topMetrics(ConcurrentMap<String, MetricAccumulator> source, int max) {
        return source.entrySet().stream()
            .map(entry -> toMetric(entry.getKey(), entry.getValue()))
            .sorted(
                Comparator
                    .comparing(PerformanceMetricResponse::getAvgMs, Comparator.reverseOrder())
                    .thenComparing(PerformanceMetricResponse::getMaxMs, Comparator.reverseOrder())
            )
            .limit(max)
            .collect(Collectors.toList());
    }

    private PerformanceMetricResponse toMetric(String name, MetricAccumulator metricAccumulator) {
        MetricSnapshot snapshot = metricAccumulator.snapshot();
        PerformanceMetricResponse response = new PerformanceMetricResponse();
        response.setName(name);
        response.setCount(snapshot.count());
        response.setAvgMs(snapshot.avgMs());
        response.setMaxMs(snapshot.maxMs());
        response.setLastMs(snapshot.lastMs());
        return response;
    }

    private record MetricSnapshot(long count, double avgMs, long maxMs, long lastMs) {
    }

    private static class MetricAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLong lastMs = new AtomicLong();

        private void record(long durationMs) {
            long safeDuration = Math.max(0, durationMs);
            count.increment();
            totalMs.add(safeDuration);
            lastMs.set(safeDuration);
            maxMs.updateAndGet(previous -> Math.max(previous, safeDuration));
        }

        private MetricSnapshot snapshot() {
            long totalCount = count.sum();
            long totalDuration = totalMs.sum();
            double avg = totalCount == 0 ? 0.0 : CalorieMath.round((double) totalDuration / totalCount);
            return new MetricSnapshot(totalCount, avg, maxMs.get(), lastMs.get());
        }
    }
}

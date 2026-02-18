package com.calorietracker.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PerformanceSummaryResponse {

    private Instant generatedAt;
    private List<PerformanceMetricResponse> apiMetrics = new ArrayList<>();
    private List<PerformanceMetricResponse> repositoryMetrics = new ArrayList<>();

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public List<PerformanceMetricResponse> getApiMetrics() {
        return apiMetrics;
    }

    public void setApiMetrics(List<PerformanceMetricResponse> apiMetrics) {
        this.apiMetrics = apiMetrics;
    }

    public List<PerformanceMetricResponse> getRepositoryMetrics() {
        return repositoryMetrics;
    }

    public void setRepositoryMetrics(List<PerformanceMetricResponse> repositoryMetrics) {
        this.repositoryMetrics = repositoryMetrics;
    }
}

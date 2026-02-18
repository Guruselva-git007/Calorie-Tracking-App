package com.calorietracker.dto;

public class PerformanceMetricResponse {

    private String name;
    private Long count;
    private Double avgMs;
    private Long maxMs;
    private Long lastMs;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    public Double getAvgMs() {
        return avgMs;
    }

    public void setAvgMs(Double avgMs) {
        this.avgMs = avgMs;
    }

    public Long getMaxMs() {
        return maxMs;
    }

    public void setMaxMs(Long maxMs) {
        this.maxMs = maxMs;
    }

    public Long getLastMs() {
        return lastMs;
    }

    public void setLastMs(Long lastMs) {
        this.lastMs = lastMs;
    }
}

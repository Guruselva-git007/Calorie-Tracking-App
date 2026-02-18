package com.calorietracker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.PerformanceSummaryResponse;
import com.calorietracker.service.PerformanceMonitorService;

@RestController
@RequestMapping("/api/perf")
public class PerformanceController {

    private final PerformanceMonitorService performanceMonitorService;

    public PerformanceController(PerformanceMonitorService performanceMonitorService) {
        this.performanceMonitorService = performanceMonitorService;
    }

    @GetMapping("/summary")
    public PerformanceSummaryResponse getSummary(@RequestParam(defaultValue = "16") Integer limit) {
        return performanceMonitorService.summary(limit);
    }
}

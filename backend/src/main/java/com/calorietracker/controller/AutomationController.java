package com.calorietracker.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.service.AppAutomationService;

@RestController
@RequestMapping("/api/automation")
public class AutomationController {

    private final AppAutomationService appAutomationService;

    public AutomationController(AppAutomationService appAutomationService) {
        this.appAutomationService = appAutomationService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return appAutomationService.getStatusSnapshot();
    }

    @PostMapping("/trigger")
    public Map<String, Object> trigger(@RequestParam(defaultValue = "manual-next") String task) {
        return appAutomationService.triggerNow(task);
    }
}

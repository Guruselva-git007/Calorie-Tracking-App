package com.calorietracker.controller;

import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.SupportFeedbackRequest;
import com.calorietracker.dto.SupportFeedbackResponse;
import com.calorietracker.service.SupportFeedbackService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/support")
@Validated
public class SupportController {

    private final SupportFeedbackService supportFeedbackService;

    public SupportController(SupportFeedbackService supportFeedbackService) {
        this.supportFeedbackService = supportFeedbackService;
    }

    @PostMapping("/feedback")
    public SupportFeedbackResponse submitFeedback(@Valid @RequestBody SupportFeedbackRequest request) {
        return supportFeedbackService.submit(request);
    }

    @GetMapping("/quick-help")
    public Map<String, Object> quickHelp() {
        return supportFeedbackService.quickHelp();
    }
}

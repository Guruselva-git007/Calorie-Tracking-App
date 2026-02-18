package com.calorietracker.controller;

import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.DeficiencyToolsRequest;
import com.calorietracker.dto.DeficiencyToolsResponse;
import com.calorietracker.service.ToolsRecommendationService;

@RestController
@RequestMapping("/api/tools")
@Validated
public class ToolsController {

    private final ToolsRecommendationService toolsRecommendationService;

    public ToolsController(ToolsRecommendationService toolsRecommendationService) {
        this.toolsRecommendationService = toolsRecommendationService;
    }

    @PostMapping("/recommendations")
    public DeficiencyToolsResponse getRecommendations(@RequestBody DeficiencyToolsRequest request) {
        DeficiencyToolsRequest payload = request == null ? new DeficiencyToolsRequest() : request;
        return toolsRecommendationService.buildRecommendations(payload);
    }

    @GetMapping("/currencies")
    public Map<String, Double> getCurrencies() {
        return toolsRecommendationService.supportedCurrencies();
    }
}

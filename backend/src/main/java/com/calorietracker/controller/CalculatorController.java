package com.calorietracker.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.CalculateIngredientsRequest;
import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.service.CalculationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/calculator")
@Validated
public class CalculatorController {

    private final CalculationService calculationService;

    public CalculatorController(CalculationService calculationService) {
        this.calculationService = calculationService;
    }

    @PostMapping("/ingredients")
    public CalculationResponse calculateIngredients(@Valid @RequestBody CalculateIngredientsRequest request) {
        return calculationService.calculateIngredients(request.getItems());
    }
}

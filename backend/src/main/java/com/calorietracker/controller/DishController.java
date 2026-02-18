package com.calorietracker.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.dto.CreateCustomDishRequest;
import com.calorietracker.dto.DishCalculationRequest;
import com.calorietracker.dto.DishResponse;
import com.calorietracker.service.DishService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/dishes")
@Validated
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    @GetMapping
    public List<DishResponse> getDishes(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String cuisine,
        @RequestParam(required = false) Integer limit
    ) {
        return dishService.findDishes(search, cuisine, limit);
    }

    @GetMapping("/suggest")
    public List<DishResponse> suggestDishes(
        @RequestParam String search,
        @RequestParam(required = false) Integer limit
    ) {
        return dishService.findDishSuggestions(search, limit);
    }

    @GetMapping("/{id}")
    public DishResponse getDish(@PathVariable Long id) {
        return dishService.getDishById(id);
    }

    @PostMapping("/custom")
    public DishResponse createCustomDish(@Valid @RequestBody CreateCustomDishRequest request) {
        return dishService.createCustomDish(request);
    }

    @PostMapping("/{id}/calculate")
    public CalculationResponse calculateDish(
        @PathVariable Long id,
        @Valid @RequestBody(required = false) DishCalculationRequest request
    ) {
        DishCalculationRequest payload = request == null ? new DishCalculationRequest() : request;
        return dishService.calculateDishCalories(id, payload.getServings(), payload.getCustomIngredients());
    }
}

package com.calorietracker.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.repository.DishRepository;
import com.calorietracker.repository.IngredientRepository;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final IngredientRepository ingredientRepository;
    private final DishRepository dishRepository;

    public StatsController(IngredientRepository ingredientRepository, DishRepository dishRepository) {
        this.ingredientRepository = ingredientRepository;
        this.dishRepository = dishRepository;
    }

    @GetMapping
    public Map<String, Long> getDatasetStats() {
        return Map.of(
            "ingredients", ingredientRepository.count(),
            "dishes", dishRepository.count()
        );
    }
}

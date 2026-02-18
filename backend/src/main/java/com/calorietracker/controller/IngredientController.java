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

import com.calorietracker.dto.CreateIngredientRequest;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.service.IngredientService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
@Validated
public class IngredientController {

    private final IngredientService ingredientService;

    public IngredientController(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    @GetMapping("/ingredients")
    public List<IngredientResponse> getIngredients(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String cuisine,
        @RequestParam(required = false) Integer limit
    ) {
        return ingredientService.findIngredients(search, category, cuisine, limit);
    }

    @GetMapping("/ingredients/{id}")
    public IngredientResponse getIngredient(@PathVariable Long id) {
        return ingredientService.getIngredientById(id);
    }

    @PostMapping("/ingredients/custom")
    public IngredientResponse createIngredient(@Valid @RequestBody CreateIngredientRequest request) {
        return ingredientService.createIngredient(request);
    }

    @GetMapping("/foods")
    public List<IngredientResponse> getFoods(
        @RequestParam(required = false, name = "search") String search,
        @RequestParam(required = false) Integer limit
    ) {
        return ingredientService.findIngredients(search, null, null, limit);
    }

    @GetMapping("/foods/search")
    public List<IngredientResponse> searchFoods(
        @RequestParam(name = "name") String name,
        @RequestParam(required = false) Integer limit
    ) {
        return ingredientService.findIngredients(name, null, null, limit);
    }
}

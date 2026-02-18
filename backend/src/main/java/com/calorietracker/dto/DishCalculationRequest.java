package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

public class DishCalculationRequest {

    @Positive
    private Double servings = 1.0;

    @Valid
    private List<IngredientLineRequest> customIngredients = new ArrayList<>();

    public Double getServings() {
        return servings;
    }

    public void setServings(Double servings) {
        this.servings = servings;
    }

    public List<IngredientLineRequest> getCustomIngredients() {
        return customIngredients;
    }

    public void setCustomIngredients(List<IngredientLineRequest> customIngredients) {
        this.customIngredients = customIngredients;
    }
}

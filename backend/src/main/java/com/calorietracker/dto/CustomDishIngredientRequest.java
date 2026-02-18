package com.calorietracker.dto;

import jakarta.validation.constraints.Positive;

public class CustomDishIngredientRequest {

    private Long ingredientId;

    private String ingredientName;

    private String category;

    private String cuisine;

    @Positive
    private Double caloriesPer100g;

    @Positive
    private Double grams;

    private Boolean factConfirmed = false;

    public Long getIngredientId() {
        return ingredientId;
    }

    public void setIngredientId(Long ingredientId) {
        this.ingredientId = ingredientId;
    }

    public String getIngredientName() {
        return ingredientName;
    }

    public void setIngredientName(String ingredientName) {
        this.ingredientName = ingredientName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCuisine() {
        return cuisine;
    }

    public void setCuisine(String cuisine) {
        this.cuisine = cuisine;
    }

    public Double getCaloriesPer100g() {
        return caloriesPer100g;
    }

    public void setCaloriesPer100g(Double caloriesPer100g) {
        this.caloriesPer100g = caloriesPer100g;
    }

    public Double getGrams() {
        return grams;
    }

    public void setGrams(Double grams) {
        this.grams = grams;
    }

    public Boolean getFactConfirmed() {
        return factConfirmed;
    }

    public void setFactConfirmed(Boolean factConfirmed) {
        this.factConfirmed = factConfirmed;
    }
}

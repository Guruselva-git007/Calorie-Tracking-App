package com.calorietracker.dto;

public class IngredientCalorieBreakdown {

    private Long ingredientId;
    private String ingredientName;
    private Double grams;
    private Double calories;
    private Double protein;
    private Double carbs;
    private Double fats;
    private Double fiber;
    private Double estimatedPriceUsd;

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

    public Double getGrams() {
        return grams;
    }

    public void setGrams(Double grams) {
        this.grams = grams;
    }

    public Double getCalories() {
        return calories;
    }

    public void setCalories(Double calories) {
        this.calories = calories;
    }

    public Double getProtein() {
        return protein;
    }

    public void setProtein(Double protein) {
        this.protein = protein;
    }

    public Double getCarbs() {
        return carbs;
    }

    public void setCarbs(Double carbs) {
        this.carbs = carbs;
    }

    public Double getFats() {
        return fats;
    }

    public void setFats(Double fats) {
        this.fats = fats;
    }

    public Double getFiber() {
        return fiber;
    }

    public void setFiber(Double fiber) {
        this.fiber = fiber;
    }

    public Double getEstimatedPriceUsd() {
        return estimatedPriceUsd;
    }

    public void setEstimatedPriceUsd(Double estimatedPriceUsd) {
        this.estimatedPriceUsd = estimatedPriceUsd;
    }
}

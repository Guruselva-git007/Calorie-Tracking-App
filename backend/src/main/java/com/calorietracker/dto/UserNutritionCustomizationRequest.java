package com.calorietracker.dto;

public class UserNutritionCustomizationRequest {

    private Double calories;
    private Double protein;
    private Double carbs;
    private Double fats;
    private Double fiber;
    private Double priceInr;

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

    public Double getPriceInr() {
        return priceInr;
    }

    public void setPriceInr(Double priceInr) {
        this.priceInr = priceInr;
    }
}

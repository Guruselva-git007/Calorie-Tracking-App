package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class CalculationResponse {

    private Double totalCalories;
    private Double totalProtein;
    private Double totalCarbs;
    private Double totalFats;
    private Double totalFiber;
    private Double estimatedTotalPriceUsd;
    private List<IngredientCalorieBreakdown> breakdown = new ArrayList<>();

    public Double getTotalCalories() {
        return totalCalories;
    }

    public void setTotalCalories(Double totalCalories) {
        this.totalCalories = totalCalories;
    }

    public Double getTotalProtein() {
        return totalProtein;
    }

    public void setTotalProtein(Double totalProtein) {
        this.totalProtein = totalProtein;
    }

    public Double getTotalCarbs() {
        return totalCarbs;
    }

    public void setTotalCarbs(Double totalCarbs) {
        this.totalCarbs = totalCarbs;
    }

    public Double getTotalFats() {
        return totalFats;
    }

    public void setTotalFats(Double totalFats) {
        this.totalFats = totalFats;
    }

    public Double getTotalFiber() {
        return totalFiber;
    }

    public void setTotalFiber(Double totalFiber) {
        this.totalFiber = totalFiber;
    }

    public Double getEstimatedTotalPriceUsd() {
        return estimatedTotalPriceUsd;
    }

    public void setEstimatedTotalPriceUsd(Double estimatedTotalPriceUsd) {
        this.estimatedTotalPriceUsd = estimatedTotalPriceUsd;
    }

    public List<IngredientCalorieBreakdown> getBreakdown() {
        return breakdown;
    }

    public void setBreakdown(List<IngredientCalorieBreakdown> breakdown) {
        this.breakdown = breakdown;
    }
}

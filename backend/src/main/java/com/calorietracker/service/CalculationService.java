package com.calorietracker.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.dto.IngredientCalorieBreakdown;
import com.calorietracker.dto.IngredientLineRequest;
import com.calorietracker.entity.Ingredient;

@Service
public class CalculationService {

    private final IngredientService ingredientService;

    public CalculationService(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    public CalculationResponse calculateIngredients(List<IngredientLineRequest> lines) {
        List<IngredientCalorieBreakdown> breakdown = new ArrayList<>();
        for (IngredientLineRequest line : lines) {
            Ingredient ingredient = ingredientService.getIngredientOrThrow(line.getIngredientId());

            IngredientCalorieBreakdown row = new IngredientCalorieBreakdown();
            row.setIngredientId(ingredient.getId());
            row.setIngredientName(ingredient.getName());
            row.setGrams(CalorieMath.round(line.getGrams()));
            row.setCalories(CalorieMath.caloriesFromGrams(ingredient.getCaloriesPer100g(), line.getGrams()));
            row.setProtein(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getProteinPer100g()), line.getGrams()));
            row.setCarbs(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getCarbsPer100g()), line.getGrams()));
            row.setFats(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getFatsPer100g()), line.getGrams()));
            row.setFiber(CalorieMath.nutrientFromGrams(valueOrZero(ingredient.getFiberPer100g()), line.getGrams()));
            row.setEstimatedPriceUsd(
                CalorieMath.priceFromBaseQuantity(valueOrZero(ingredient.getAveragePriceUsd()), ingredient.getAveragePriceUnit(), line.getGrams())
            );
            breakdown.add(row);
        }

        CalculationResponse response = new CalculationResponse();
        response.setBreakdown(breakdown);
        response.setTotalCalories(CalorieMath.round(breakdown.stream().mapToDouble(IngredientCalorieBreakdown::getCalories).sum()));
        response.setTotalProtein(CalorieMath.round(breakdown.stream().mapToDouble(item -> valueOrZero(item.getProtein())).sum()));
        response.setTotalCarbs(CalorieMath.round(breakdown.stream().mapToDouble(item -> valueOrZero(item.getCarbs())).sum()));
        response.setTotalFats(CalorieMath.round(breakdown.stream().mapToDouble(item -> valueOrZero(item.getFats())).sum()));
        response.setTotalFiber(CalorieMath.round(breakdown.stream().mapToDouble(item -> valueOrZero(item.getFiber())).sum()));
        response.setEstimatedTotalPriceUsd(
            CalorieMath.round(breakdown.stream().mapToDouble(item -> valueOrZero(item.getEstimatedPriceUsd())).sum())
        );
        return response;
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }
}

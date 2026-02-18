package com.calorietracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CalorieMath {

    private CalorieMath() {
    }

    public static double caloriesFromGrams(double caloriesPer100g, double grams) {
        return round((caloriesPer100g * grams) / 100.0);
    }

    public static double nutrientFromGrams(double nutrientPer100g, double grams) {
        return round((nutrientPer100g * grams) / 100.0);
    }

    public static double priceFromBaseQuantity(double pricePerBaseUnit, String baseUnit, double grams) {
        if (pricePerBaseUnit <= 0) {
            return 0.0;
        }

        String unit = baseUnit == null ? "kg" : baseUnit.trim().toLowerCase();
        if ("kg".equals(unit)) {
            return round(pricePerBaseUnit * (grams / 1000.0));
        }
        if ("l".equals(unit)) {
            return round(pricePerBaseUnit * (grams / 1000.0));
        }
        return round(pricePerBaseUnit * (grams / 1000.0));
    }

    public static double toGrams(double quantity, String unit) {
        String cleanUnit = unit == null ? "g" : unit.trim().toLowerCase();
        return switch (cleanUnit) {
            case "kg" -> round(quantity * 1000.0);
            case "l" -> round(quantity * 1000.0);
            case "ml" -> round(quantity);
            case "g" -> round(quantity);
            default -> round(quantity);
        };
    }

    public static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

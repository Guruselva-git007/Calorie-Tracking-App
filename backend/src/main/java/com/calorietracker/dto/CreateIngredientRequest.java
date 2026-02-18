package com.calorietracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class CreateIngredientRequest {

    @NotBlank
    @Size(max = 180)
    private String name;

    @NotBlank
    private String category;

    @NotBlank
    @Size(max = 120)
    private String cuisine;

    @NotNull
    @Positive
    private Double caloriesPer100g;

    @NotBlank
    @Size(max = 80)
    private String servingNote;

    @PositiveOrZero
    private Double proteinPer100g;

    @PositiveOrZero
    private Double carbsPer100g;

    @PositiveOrZero
    private Double fatsPer100g;

    @PositiveOrZero
    private Double fiberPer100g;

    @Positive
    private Double averagePriceUsd;

    @Size(max = 12)
    private String averagePriceUnit;

    private List<String> aliases = new ArrayList<>();

    private List<String> regionalAvailability = new ArrayList<>();

    @NotNull
    private Boolean factConfirmed;

    @Size(max = 50)
    private String source;

    @Size(max = 900)
    private String imageUrl;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getServingNote() {
        return servingNote;
    }

    public void setServingNote(String servingNote) {
        this.servingNote = servingNote;
    }

    public Boolean getFactConfirmed() {
        return factConfirmed;
    }

    public void setFactConfirmed(Boolean factConfirmed) {
        this.factConfirmed = factConfirmed;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getProteinPer100g() {
        return proteinPer100g;
    }

    public void setProteinPer100g(Double proteinPer100g) {
        this.proteinPer100g = proteinPer100g;
    }

    public Double getCarbsPer100g() {
        return carbsPer100g;
    }

    public void setCarbsPer100g(Double carbsPer100g) {
        this.carbsPer100g = carbsPer100g;
    }

    public Double getFatsPer100g() {
        return fatsPer100g;
    }

    public void setFatsPer100g(Double fatsPer100g) {
        this.fatsPer100g = fatsPer100g;
    }

    public Double getFiberPer100g() {
        return fiberPer100g;
    }

    public void setFiberPer100g(Double fiberPer100g) {
        this.fiberPer100g = fiberPer100g;
    }

    public Double getAveragePriceUsd() {
        return averagePriceUsd;
    }

    public void setAveragePriceUsd(Double averagePriceUsd) {
        this.averagePriceUsd = averagePriceUsd;
    }

    public String getAveragePriceUnit() {
        return averagePriceUnit;
    }

    public void setAveragePriceUnit(String averagePriceUnit) {
        this.averagePriceUnit = averagePriceUnit;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public List<String> getRegionalAvailability() {
        return regionalAvailability;
    }

    public void setRegionalAvailability(List<String> regionalAvailability) {
        this.regionalAvailability = regionalAvailability;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}

package com.calorietracker.dto;

import com.calorietracker.entity.IngredientCategory;
import java.util.ArrayList;
import java.util.List;

public class IngredientResponse {

    private Long id;
    private String name;
    private IngredientCategory category;
    private String cuisine;
    private Double caloriesPer100g;
    private Double proteinPer100g;
    private Double carbsPer100g;
    private Double fatsPer100g;
    private Double fiberPer100g;
    private Double averagePriceUsd;
    private String averagePriceUnit;
    private String servingNote;
    private Boolean factChecked;
    private String source;
    private Boolean personalized = false;
    private List<String> aliases = new ArrayList<>();
    private List<String> regionalAvailability = new ArrayList<>();
    private String imageUrl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public IngredientCategory getCategory() {
        return category;
    }

    public void setCategory(IngredientCategory category) {
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

    public String getServingNote() {
        return servingNote;
    }

    public void setServingNote(String servingNote) {
        this.servingNote = servingNote;
    }

    public Boolean getFactChecked() {
        return factChecked;
    }

    public void setFactChecked(Boolean factChecked) {
        this.factChecked = factChecked;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getPersonalized() {
        return personalized;
    }

    public void setPersonalized(Boolean personalized) {
        this.personalized = personalized;
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

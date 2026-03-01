package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class DishResponse {

    private Long id;
    private String name;
    private String cuisine;
    private String description;
    private Double caloriesPerServing;
    private Double proteinPerServing;
    private Double carbsPerServing;
    private Double fatsPerServing;
    private Double fiberPerServing;
    private Double estimatedPriceUsdPerServing;
    private Boolean factChecked;
    private String source;
    private Boolean personalized = false;
    private String imageUrl;
    private List<DishComponentResponse> components = new ArrayList<>();

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

    public String getCuisine() {
        return cuisine;
    }

    public void setCuisine(String cuisine) {
        this.cuisine = cuisine;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Double getCaloriesPerServing() {
        return caloriesPerServing;
    }

    public void setCaloriesPerServing(Double caloriesPerServing) {
        this.caloriesPerServing = caloriesPerServing;
    }

    public Double getProteinPerServing() {
        return proteinPerServing;
    }

    public void setProteinPerServing(Double proteinPerServing) {
        this.proteinPerServing = proteinPerServing;
    }

    public Double getCarbsPerServing() {
        return carbsPerServing;
    }

    public void setCarbsPerServing(Double carbsPerServing) {
        this.carbsPerServing = carbsPerServing;
    }

    public Double getFatsPerServing() {
        return fatsPerServing;
    }

    public void setFatsPerServing(Double fatsPerServing) {
        this.fatsPerServing = fatsPerServing;
    }

    public Double getFiberPerServing() {
        return fiberPerServing;
    }

    public void setFiberPerServing(Double fiberPerServing) {
        this.fiberPerServing = fiberPerServing;
    }

    public Double getEstimatedPriceUsdPerServing() {
        return estimatedPriceUsdPerServing;
    }

    public void setEstimatedPriceUsdPerServing(Double estimatedPriceUsdPerServing) {
        this.estimatedPriceUsdPerServing = estimatedPriceUsdPerServing;
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

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<DishComponentResponse> getComponents() {
        return components;
    }

    public void setComponents(List<DishComponentResponse> components) {
        this.components = components;
    }
}

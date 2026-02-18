package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateCustomDishRequest {

    @NotBlank
    @Size(max = 180)
    private String name;

    @NotBlank
    @Size(max = 120)
    private String cuisine;

    @Size(max = 500)
    private String description;

    @NotEmpty
    @Valid
    private List<CustomDishIngredientRequest> ingredients = new ArrayList<>();

    @NotNull
    private Boolean confirmAllFacts;

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

    public List<CustomDishIngredientRequest> getIngredients() {
        return ingredients;
    }

    public void setIngredients(List<CustomDishIngredientRequest> ingredients) {
        this.ingredients = ingredients;
    }

    public Boolean getConfirmAllFacts() {
        return confirmAllFacts;
    }

    public void setConfirmAllFacts(Boolean confirmAllFacts) {
        this.confirmAllFacts = confirmAllFacts;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}

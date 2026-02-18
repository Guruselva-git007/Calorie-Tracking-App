package com.calorietracker.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateDishEntryRequest {

    @NotNull
    private Long userId;

    @NotNull
    private Long dishId;

    @NotNull
    @Positive
    private Double servings;

    @Valid
    private List<IngredientLineRequest> customIngredients = new ArrayList<>();

    private LocalDate entryDate;

    private String note;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDishId() {
        return dishId;
    }

    public void setDishId(Long dishId) {
        this.dishId = dishId;
    }

    public Double getServings() {
        return servings;
    }

    public void setServings(Double servings) {
        this.servings = servings;
    }

    public List<IngredientLineRequest> getCustomIngredients() {
        return customIngredients;
    }

    public void setCustomIngredients(List<IngredientLineRequest> customIngredients) {
        this.customIngredients = customIngredients;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}

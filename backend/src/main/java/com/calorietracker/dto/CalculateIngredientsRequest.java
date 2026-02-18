package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public class CalculateIngredientsRequest {

    @NotEmpty
    @Valid
    private List<IngredientLineRequest> items = new ArrayList<>();

    public List<IngredientLineRequest> getItems() {
        return items;
    }

    public void setItems(List<IngredientLineRequest> items) {
        this.items = items;
    }
}

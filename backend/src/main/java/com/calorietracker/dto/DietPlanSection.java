package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class DietPlanSection {

    private String title;
    private List<String> items = new ArrayList<>();

    public DietPlanSection() {
    }

    public DietPlanSection(String title, List<String> items) {
        this.title = title;
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }
}

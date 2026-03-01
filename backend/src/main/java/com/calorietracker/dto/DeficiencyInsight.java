package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class DeficiencyInsight {

    private String name;
    private String category;
    private String tip;
    private List<String> symptoms = new ArrayList<>();
    private List<String> recommendedFoods = new ArrayList<>();
    private List<String> naturalFoodPlan = new ArrayList<>();
    private List<String> recommendedSupplements = new ArrayList<>();
    private List<String> sources = new ArrayList<>();

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

    public String getTip() {
        return tip;
    }

    public void setTip(String tip) {
        this.tip = tip;
    }

    public List<String> getSymptoms() {
        return symptoms;
    }

    public void setSymptoms(List<String> symptoms) {
        this.symptoms = symptoms == null ? new ArrayList<>() : symptoms;
    }

    public List<String> getRecommendedFoods() {
        return recommendedFoods;
    }

    public void setRecommendedFoods(List<String> recommendedFoods) {
        this.recommendedFoods = recommendedFoods == null ? new ArrayList<>() : recommendedFoods;
    }

    public List<String> getNaturalFoodPlan() {
        return naturalFoodPlan;
    }

    public void setNaturalFoodPlan(List<String> naturalFoodPlan) {
        this.naturalFoodPlan = naturalFoodPlan == null ? new ArrayList<>() : naturalFoodPlan;
    }

    public List<String> getRecommendedSupplements() {
        return recommendedSupplements;
    }

    public void setRecommendedSupplements(List<String> recommendedSupplements) {
        this.recommendedSupplements = recommendedSupplements == null ? new ArrayList<>() : recommendedSupplements;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }
}

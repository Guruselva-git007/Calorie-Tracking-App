package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class DeficiencyToolsRequest {

    private String region;
    private String dietaryPreference;
    private Boolean includeSupplements = true;
    private Integer dailyCalorieGoal;
    private List<String> deficiencies = new ArrayList<>();
    private List<String> medicalConditions = new ArrayList<>();
    private List<String> likedFoods = new ArrayList<>();
    private List<String> currencies = new ArrayList<>();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDietaryPreference() {
        return dietaryPreference;
    }

    public void setDietaryPreference(String dietaryPreference) {
        this.dietaryPreference = dietaryPreference;
    }

    public Boolean getIncludeSupplements() {
        return includeSupplements;
    }

    public void setIncludeSupplements(Boolean includeSupplements) {
        this.includeSupplements = includeSupplements;
    }

    public List<String> getDeficiencies() {
        return deficiencies;
    }

    public void setDeficiencies(List<String> deficiencies) {
        this.deficiencies = deficiencies;
    }

    public Integer getDailyCalorieGoal() {
        return dailyCalorieGoal;
    }

    public void setDailyCalorieGoal(Integer dailyCalorieGoal) {
        this.dailyCalorieGoal = dailyCalorieGoal;
    }

    public List<String> getCurrencies() {
        return currencies;
    }

    public void setCurrencies(List<String> currencies) {
        this.currencies = currencies;
    }

    public List<String> getMedicalConditions() {
        return medicalConditions;
    }

    public void setMedicalConditions(List<String> medicalConditions) {
        this.medicalConditions = medicalConditions;
    }

    public List<String> getLikedFoods() {
        return likedFoods;
    }

    public void setLikedFoods(List<String> likedFoods) {
        this.likedFoods = likedFoods;
    }
}

package com.calorietracker.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DeficiencyToolsResponse {

    private String region;
    private String dietaryPreference;
    private List<String> deficiencies = new ArrayList<>();
    private List<String> medicalConditions = new ArrayList<>();
    private List<String> currencies = new ArrayList<>();
    private Instant generatedAt;
    private List<DeficiencyRecommendationItem> recommendations = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

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

    public List<String> getDeficiencies() {
        return deficiencies;
    }

    public void setDeficiencies(List<String> deficiencies) {
        this.deficiencies = deficiencies;
    }

    public List<String> getMedicalConditions() {
        return medicalConditions;
    }

    public void setMedicalConditions(List<String> medicalConditions) {
        this.medicalConditions = medicalConditions;
    }

    public List<String> getCurrencies() {
        return currencies;
    }

    public void setCurrencies(List<String> currencies) {
        this.currencies = currencies;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public List<DeficiencyRecommendationItem> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<DeficiencyRecommendationItem> recommendations) {
        this.recommendations = recommendations;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }
}

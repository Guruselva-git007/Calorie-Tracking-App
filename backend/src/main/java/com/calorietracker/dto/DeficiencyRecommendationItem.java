package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeficiencyRecommendationItem {

    private String name;
    private String type;
    private String reason;
    private String cuisine;
    private String servingNote;
    private Double caloriesPer100g;
    private Double proteinPer100g;
    private Double carbsPer100g;
    private Double fatsPer100g;
    private Double fiberPer100g;
    private Double averagePriceUsd;
    private String averagePriceUnit;
    private Map<String, Double> priceByCurrency = new LinkedHashMap<>();
    private List<String> regionalAvailability = new ArrayList<>();
    private String source;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCuisine() {
        return cuisine;
    }

    public void setCuisine(String cuisine) {
        this.cuisine = cuisine;
    }

    public String getServingNote() {
        return servingNote;
    }

    public void setServingNote(String servingNote) {
        this.servingNote = servingNote;
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

    public Map<String, Double> getPriceByCurrency() {
        return priceByCurrency;
    }

    public void setPriceByCurrency(Map<String, Double> priceByCurrency) {
        this.priceByCurrency = priceByCurrency;
    }

    public List<String> getRegionalAvailability() {
        return regionalAvailability;
    }

    public void setRegionalAvailability(List<String> regionalAvailability) {
        this.regionalAvailability = regionalAvailability;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

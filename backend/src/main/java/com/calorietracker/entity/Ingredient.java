package com.calorietracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "ingredient",
    indexes = {
        @Index(name = "idx_ingredient_name", columnList = "name"),
        @Index(name = "idx_ingredient_cuisine", columnList = "cuisine")
    }
)
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 180)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IngredientCategory category;

    @Column(nullable = false, length = 120)
    private String cuisine;

    @Column(nullable = false)
    private Double caloriesPer100g;

    @Column
    private Double proteinPer100g;

    @Column
    private Double carbsPer100g;

    @Column
    private Double fatsPer100g;

    @Column
    private Double fiberPer100g;

    @Column
    private Double averagePriceUsd;

    @Column(length = 12)
    private String averagePriceUnit;

    @Column(nullable = false, length = 80)
    private String servingNote;

    @Column
    private Boolean factChecked = true;

    @Column(length = 50)
    private String source = "CURATED";

    @Column(length = 600)
    private String aliases;

    @Column(length = 400)
    private String regionalAvailability;

    @Column(length = 900)
    private String imageUrl;

    public Ingredient() {
    }

    public Ingredient(String name, IngredientCategory category, String cuisine, Double caloriesPer100g, String servingNote) {
        this.name = name;
        this.category = category;
        this.cuisine = cuisine;
        this.caloriesPer100g = caloriesPer100g;
        this.servingNote = servingNote;
    }

    public Ingredient(
        String name,
        IngredientCategory category,
        String cuisine,
        Double caloriesPer100g,
        String servingNote,
        Boolean factChecked,
        String source
    ) {
        this.name = name;
        this.category = category;
        this.cuisine = cuisine;
        this.caloriesPer100g = caloriesPer100g;
        this.servingNote = servingNote;
        this.factChecked = factChecked;
        this.source = source;
    }

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

    public String getAliases() {
        return aliases;
    }

    public void setAliases(String aliases) {
        this.aliases = aliases;
    }

    public String getRegionalAvailability() {
        return regionalAvailability;
    }

    public void setRegionalAvailability(String regionalAvailability) {
        this.regionalAvailability = regionalAvailability;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}

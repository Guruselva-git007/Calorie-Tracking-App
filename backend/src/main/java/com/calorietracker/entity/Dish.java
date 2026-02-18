package com.calorietracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "dish",
    indexes = {
        @Index(name = "idx_dish_name", columnList = "name"),
        @Index(name = "idx_dish_cuisine", columnList = "cuisine")
    }
)
public class Dish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 180)
    private String name;

    @Column(nullable = false, length = 120)
    private String cuisine;

    @Column(nullable = false, length = 500)
    private String description;

    @Column
    private Boolean factChecked = true;

    @Column(length = 50)
    private String source = "CURATED";

    @Column(length = 900)
    private String imageUrl;

    public Dish() {
    }

    public Dish(String name, String cuisine, String description) {
        this.name = name;
        this.cuisine = cuisine;
        this.description = description;
    }

    public Dish(String name, String cuisine, String description, Boolean factChecked, String source) {
        this.name = name;
        this.cuisine = cuisine;
        this.description = description;
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

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}

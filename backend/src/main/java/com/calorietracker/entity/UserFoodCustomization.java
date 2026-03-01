package com.calorietracker.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "user_food_customization",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_food_customization", columnNames = {"user_id", "food_type", "food_id"})
    },
    indexes = {
        @Index(name = "idx_user_food_customization_user", columnList = "user_id"),
        @Index(name = "idx_user_food_customization_updated", columnList = "updated_at")
    }
)
public class UserFoodCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_type", nullable = false, length = 16)
    private PersonalizedFoodType foodType;

    @Column(name = "food_id", nullable = false)
    private Long foodId;

    @Column(name = "food_name", nullable = false, length = 180)
    private String foodName;

    @Column(name = "calories_value")
    private Double caloriesValue;

    @Column(name = "protein_value")
    private Double proteinValue;

    @Column(name = "carbs_value")
    private Double carbsValue;

    @Column(name = "fats_value")
    private Double fatsValue;

    @Column(name = "fiber_value")
    private Double fiberValue;

    @Column(name = "price_usd_value")
    private Double priceUsdValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public PersonalizedFoodType getFoodType() {
        return foodType;
    }

    public void setFoodType(PersonalizedFoodType foodType) {
        this.foodType = foodType;
    }

    public Long getFoodId() {
        return foodId;
    }

    public void setFoodId(Long foodId) {
        this.foodId = foodId;
    }

    public String getFoodName() {
        return foodName;
    }

    public void setFoodName(String foodName) {
        this.foodName = foodName;
    }

    public Double getCaloriesValue() {
        return caloriesValue;
    }

    public void setCaloriesValue(Double caloriesValue) {
        this.caloriesValue = caloriesValue;
    }

    public Double getProteinValue() {
        return proteinValue;
    }

    public void setProteinValue(Double proteinValue) {
        this.proteinValue = proteinValue;
    }

    public Double getCarbsValue() {
        return carbsValue;
    }

    public void setCarbsValue(Double carbsValue) {
        this.carbsValue = carbsValue;
    }

    public Double getFatsValue() {
        return fatsValue;
    }

    public void setFatsValue(Double fatsValue) {
        this.fatsValue = fatsValue;
    }

    public Double getFiberValue() {
        return fiberValue;
    }

    public void setFiberValue(Double fiberValue) {
        this.fiberValue = fiberValue;
    }

    public Double getPriceUsdValue() {
        return priceUsdValue;
    }

    public void setPriceUsdValue(Double priceUsdValue) {
        this.priceUsdValue = priceUsdValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

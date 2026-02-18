package com.calorietracker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public class UpdateUserProfileRequest {

    @Size(max = 120)
    private String name;

    @Size(max = 80)
    private String nickname;

    @Min(1)
    @Max(120)
    private Integer age;

    @Size(max = 8)
    private String bloodGroup;

    @DecimalMin("50.0")
    @DecimalMax("280.0")
    private Double heightCm;

    @DecimalMin("20.0")
    @DecimalMax("500.0")
    private Double weightKg;

    @Size(max = 120)
    private String region;

    @Size(max = 120)
    private String state;

    @Size(max = 120)
    private String city;

    @Size(max = 600)
    private String nutritionDeficiency;

    @Size(max = 600)
    private String medicalIllness;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getBloodGroup() {
        return bloodGroup;
    }

    public void setBloodGroup(String bloodGroup) {
        this.bloodGroup = bloodGroup;
    }

    public Double getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(Double heightCm) {
        this.heightCm = heightCm;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(Double weightKg) {
        this.weightKg = weightKg;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getNutritionDeficiency() {
        return nutritionDeficiency;
    }

    public void setNutritionDeficiency(String nutritionDeficiency) {
        this.nutritionDeficiency = nutritionDeficiency;
    }

    public String getMedicalIllness() {
        return medicalIllness;
    }

    public void setMedicalIllness(String medicalIllness) {
        this.medicalIllness = medicalIllness;
    }
}

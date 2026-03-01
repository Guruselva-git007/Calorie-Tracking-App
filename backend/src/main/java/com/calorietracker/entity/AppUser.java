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
    name = "app_user",
    indexes = {
        @Index(name = "idx_user_email", columnList = "email"),
        @Index(name = "idx_user_phone", columnList = "phone"),
        @Index(name = "idx_user_nickname", columnList = "nickname")
    }
)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 80)
    private String nickname;

    @Column(unique = true, length = 180)
    private String email;

    @Column(unique = true, length = 30)
    private String phone;

    @Column
    private Boolean emailVerified = false;

    @Column
    private Boolean phoneVerified = false;

    @Column(nullable = false)
    private Integer dailyCalorieGoal;

    @Column
    private Integer age;

    @Column(length = 8)
    private String bloodGroup;

    @Column
    private Double heightCm;

    @Column
    private Double weightKg;

    @Column(length = 120)
    private String region;

    @Column(length = 120)
    private String state;

    @Column(length = 120)
    private String city;

    @Column(length = 600)
    private String nutritionDeficiency;

    @Column(length = 600)
    private String medicalIllness;

    @Column(length = 1200)
    private String likedFoods;

    @Column(columnDefinition = "TEXT")
    private String profileImageUrl;

    public AppUser() {
    }

    public AppUser(String name, Integer dailyCalorieGoal) {
        this.name = name;
        this.dailyCalorieGoal = dailyCalorieGoal;
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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Boolean getPhoneVerified() {
        return phoneVerified;
    }

    public void setPhoneVerified(Boolean phoneVerified) {
        this.phoneVerified = phoneVerified;
    }

    public Integer getDailyCalorieGoal() {
        return dailyCalorieGoal;
    }

    public void setDailyCalorieGoal(Integer dailyCalorieGoal) {
        this.dailyCalorieGoal = dailyCalorieGoal;
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

    public String getLikedFoods() {
        return likedFoods;
    }

    public void setLikedFoods(String likedFoods) {
        this.likedFoods = likedFoods;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }
}

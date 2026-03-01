package com.calorietracker.dto;

public class AuthUserResponse {

    private Long id;
    private String name;
    private String nickname;
    private String email;
    private String phone;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private Integer dailyCalorieGoal;
    private Integer age;
    private String bloodGroup;
    private Double heightCm;
    private Double weightKg;
    private String region;
    private String state;
    private String city;
    private String nutritionDeficiency;
    private String medicalIllness;
    private String likedFoods;
    private String profileImageUrl;

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

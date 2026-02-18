package com.calorietracker.dto;

import java.time.LocalDate;

public class DaySummaryResponse {

    private LocalDate date;
    private Double totalCalories;
    private Integer dailyGoal;
    private Double remainingCalories;
    private Integer entryCount;

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Double getTotalCalories() {
        return totalCalories;
    }

    public void setTotalCalories(Double totalCalories) {
        this.totalCalories = totalCalories;
    }

    public Integer getDailyGoal() {
        return dailyGoal;
    }

    public void setDailyGoal(Integer dailyGoal) {
        this.dailyGoal = dailyGoal;
    }

    public Double getRemainingCalories() {
        return remainingCalories;
    }

    public void setRemainingCalories(Double remainingCalories) {
        this.remainingCalories = remainingCalories;
    }

    public Integer getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(Integer entryCount) {
        this.entryCount = entryCount;
    }
}

package com.calorietracker.dto;

import java.time.LocalDate;

public class DaySummaryResponse {

    private LocalDate date;
    private Double totalCalories;
    private Integer dailyGoal;
    private Double remainingCalories;
    private Integer entryCount;
    private Double totalProtein;
    private Double totalCarbs;
    private Double totalFats;
    private Double totalFiber;

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

    public Double getTotalProtein() {
        return totalProtein;
    }

    public void setTotalProtein(Double totalProtein) {
        this.totalProtein = totalProtein;
    }

    public Double getTotalCarbs() {
        return totalCarbs;
    }

    public void setTotalCarbs(Double totalCarbs) {
        this.totalCarbs = totalCarbs;
    }

    public Double getTotalFats() {
        return totalFats;
    }

    public void setTotalFats(Double totalFats) {
        this.totalFats = totalFats;
    }

    public Double getTotalFiber() {
        return totalFiber;
    }

    public void setTotalFiber(Double totalFiber) {
        this.totalFiber = totalFiber;
    }
}

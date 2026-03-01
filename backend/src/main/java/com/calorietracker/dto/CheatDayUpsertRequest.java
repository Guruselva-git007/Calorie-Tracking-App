package com.calorietracker.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CheatDayUpsertRequest {

    @NotNull
    private Long userId;

    @NotNull
    private LocalDate date;

    @Size(max = 120)
    private String title;

    @Size(max = 1200)
    private String note;

    @Min(1)
    @Max(5)
    private Integer indulgenceLevel;

    @Min(0)
    @Max(5000)
    private Integer estimatedExtraCalories;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getIndulgenceLevel() {
        return indulgenceLevel;
    }

    public void setIndulgenceLevel(Integer indulgenceLevel) {
        this.indulgenceLevel = indulgenceLevel;
    }

    public Integer getEstimatedExtraCalories() {
        return estimatedExtraCalories;
    }

    public void setEstimatedExtraCalories(Integer estimatedExtraCalories) {
        this.estimatedExtraCalories = estimatedExtraCalories;
    }
}

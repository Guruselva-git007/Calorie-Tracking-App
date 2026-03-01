package com.calorietracker.dto;

import java.time.Instant;
import java.time.LocalDate;

public class CheatDayEntryResponse {

    private Long id;
    private Long userId;
    private LocalDate date;
    private String title;
    private String note;
    private Integer indulgenceLevel;
    private Integer estimatedExtraCalories;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

package com.calorietracker.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
    name = "cheat_day_record",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_cheat_day_user_date", columnNames = {"user_id", "cheat_date"})
    },
    indexes = {
        @Index(name = "idx_cheat_day_user", columnList = "user_id"),
        @Index(name = "idx_cheat_day_date", columnList = "cheat_date")
    }
)
public class CheatDayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "cheat_date", nullable = false)
    private LocalDate cheatDate;

    @Column(length = 120)
    private String title;

    @Column(length = 1200)
    private String note;

    @Column
    private Integer indulgenceLevel;

    @Column
    private Integer estimatedExtraCalories;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
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

    public LocalDate getCheatDate() {
        return cheatDate;
    }

    public void setCheatDate(LocalDate cheatDate) {
        this.cheatDate = cheatDate;
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

package com.calorietracker.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.CalorieEntry;

public interface CalorieEntryRepository extends JpaRepository<CalorieEntry, Long> {

    List<CalorieEntry> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<CalorieEntry> findByUserIdAndEntryDateOrderByCreatedAtDesc(Long userId, LocalDate entryDate);
}

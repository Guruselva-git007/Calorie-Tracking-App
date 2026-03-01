package com.calorietracker.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.SupportFeedback;

public interface SupportFeedbackRepository extends JpaRepository<SupportFeedback, Long> {
}

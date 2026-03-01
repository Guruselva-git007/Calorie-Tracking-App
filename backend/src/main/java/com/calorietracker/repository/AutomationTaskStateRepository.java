package com.calorietracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.AutomationTaskState;

public interface AutomationTaskStateRepository extends JpaRepository<AutomationTaskState, Long> {

    Optional<AutomationTaskState> findFirstByTaskKey(String taskKey);

    List<AutomationTaskState> findAllByOrderByTaskKeyAsc();
}

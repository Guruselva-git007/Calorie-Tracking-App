package com.calorietracker.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.CheatDayRecord;

public interface CheatDayRecordRepository extends JpaRepository<CheatDayRecord, Long> {

    Optional<CheatDayRecord> findFirstByUserIdAndCheatDate(Long userId, LocalDate cheatDate);

    List<CheatDayRecord> findTop180ByUserIdOrderByCheatDateDesc(Long userId);
}

package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.AuthVerificationCode;

public interface AuthVerificationCodeRepository extends JpaRepository<AuthVerificationCode, Long> {

    Optional<AuthVerificationCode> findFirstByTargetTypeAndTargetValueOrderByCreatedAtDesc(String targetType, String targetValue);
}

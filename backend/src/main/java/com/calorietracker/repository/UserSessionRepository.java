package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findFirstBySessionTokenAndRevokedFalse(String sessionToken);
}

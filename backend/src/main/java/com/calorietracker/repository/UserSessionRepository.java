package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import com.calorietracker.entity.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserSession> findFirstBySessionTokenAndRevokedFalse(String sessionToken);
}

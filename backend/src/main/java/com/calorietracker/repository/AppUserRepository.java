package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findFirstByEmailIgnoreCase(String email);

    Optional<AppUser> findFirstByPhone(String phone);
}

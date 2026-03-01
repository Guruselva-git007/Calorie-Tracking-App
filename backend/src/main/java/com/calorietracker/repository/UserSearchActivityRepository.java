package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.SearchActivityItemType;
import com.calorietracker.entity.UserSearchActivity;

import java.util.List;

public interface UserSearchActivityRepository extends JpaRepository<UserSearchActivity, Long> {

    Optional<UserSearchActivity> findFirstByUserIdAndItemTypeAndItemId(Long userId, SearchActivityItemType itemType, Long itemId);

    List<UserSearchActivity> findByUserIdOrderByLastSearchedAtDesc(Long userId, Pageable pageable);
}

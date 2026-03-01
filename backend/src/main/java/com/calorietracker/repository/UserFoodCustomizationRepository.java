package com.calorietracker.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.PersonalizedFoodType;
import com.calorietracker.entity.UserFoodCustomization;

public interface UserFoodCustomizationRepository extends JpaRepository<UserFoodCustomization, Long> {

    Optional<UserFoodCustomization> findFirstByUserIdAndFoodTypeAndFoodId(Long userId, PersonalizedFoodType foodType, Long foodId);

    List<UserFoodCustomization> findByUserIdAndFoodTypeAndFoodIdIn(Long userId, PersonalizedFoodType foodType, Collection<Long> foodIds);

    void deleteByUserIdAndFoodTypeAndFoodId(Long userId, PersonalizedFoodType foodType, Long foodId);
}

package com.calorietracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.Dish;

public interface DishRepository extends JpaRepository<Dish, Long> {

    List<Dish> findTop80ByNameStartingWithIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop120ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop200ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop200ByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByNameAsc(String name, String description);

    List<Dish> findTop200ByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<Dish> findFirstByNameIgnoreCase(String name);
}

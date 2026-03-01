package com.calorietracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import com.calorietracker.entity.Dish;

public interface DishRepository extends JpaRepository<Dish, Long> {

    List<Dish> findTop80ByNameStartingWithIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop120ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop200ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Dish> findTop200ByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByNameAsc(String name, String description);

    List<Dish> findTop200ByOrderByNameAsc();

    List<Dish> findByNameStartingWithIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    List<Dish> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    List<Dish> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCaseOrderByNameAsc(
        String name,
        String description,
        Pageable pageable
    );

    List<Dish> findByOrderByNameAsc(Pageable pageable);

    boolean existsByNameIgnoreCase(String name);

    Optional<Dish> findFirstByNameIgnoreCase(String name);
}

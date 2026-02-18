package com.calorietracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findTop180ByNameStartingWithIgnoreCaseOrderByNameAsc(String name);

    List<Ingredient> findTop180ByAliasesStartingWithIgnoreCaseOrderByNameAsc(String alias);

    List<Ingredient> findTop300ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Ingredient> findTop300ByAliasesContainingIgnoreCaseOrderByNameAsc(String alias);

    List<Ingredient> findTop500ByOrderByNameAsc();

    List<Ingredient> findTop1200ByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<Ingredient> findFirstByNameIgnoreCase(String name);
}

package com.calorietracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import com.calorietracker.entity.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    List<Ingredient> findTop180ByNameStartingWithIgnoreCaseOrderByNameAsc(String name);

    List<Ingredient> findTop180ByAliasesStartingWithIgnoreCaseOrderByNameAsc(String alias);

    List<Ingredient> findTop300ByNameContainingIgnoreCaseOrderByNameAsc(String name);

    List<Ingredient> findTop300ByAliasesContainingIgnoreCaseOrderByNameAsc(String alias);

    List<Ingredient> findByNameStartingWithIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    List<Ingredient> findByAliasesStartingWithIgnoreCaseOrderByNameAsc(String alias, Pageable pageable);

    List<Ingredient> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    List<Ingredient> findByAliasesContainingIgnoreCaseOrderByNameAsc(String alias, Pageable pageable);

    List<Ingredient> findByOrderByNameAsc(Pageable pageable);

    List<Ingredient> findTop500ByOrderByNameAsc();

    List<Ingredient> findTop1200ByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<Ingredient> findFirstByNameIgnoreCase(String name);
}

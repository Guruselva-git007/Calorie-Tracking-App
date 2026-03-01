package com.calorietracker.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.Dish;
import com.calorietracker.entity.DishComponent;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.repository.AppUserRepository;
import com.calorietracker.repository.DishComponentRepository;
import com.calorietracker.repository.DishRepository;
import com.calorietracker.repository.IngredientRepository;

@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final IngredientRepository ingredientRepository;
    private final DishRepository dishRepository;
    private final DishComponentRepository dishComponentRepository;
    private final FoodMetadataService foodMetadataService;
    private final boolean backfillOnStartup;

    public DataSeeder(
        AppUserRepository appUserRepository,
        IngredientRepository ingredientRepository,
        DishRepository dishRepository,
        DishComponentRepository dishComponentRepository,
        FoodMetadataService foodMetadataService,
        @Value("${app.seed.backfill-on-startup:false}") boolean backfillOnStartup
    ) {
        this.appUserRepository = appUserRepository;
        this.ingredientRepository = ingredientRepository;
        this.dishRepository = dishRepository;
        this.dishComponentRepository = dishComponentRepository;
        this.foodMetadataService = foodMetadataService;
        this.backfillOnStartup = backfillOnStartup;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedDefaultUser();
        seedIngredients();
        seedDishes();
        if (backfillOnStartup) {
            backfillIngredientMetadata();
            backfillDishMetadata();
        }
    }

    private void seedDefaultUser() {
        if (appUserRepository.count() == 0) {
            appUserRepository.save(new AppUser("Default User", 2200));
        }
    }

    private void seedIngredients() {
        int seedCount = GlobalFoodDataset.ingredients().size();
        long existingCount = ingredientRepository.count();
        if (existingCount >= seedCount) {
            return;
        }

        Set<String> existingNames = new HashSet<>();

        for (GlobalFoodDataset.IngredientSeed seed : GlobalFoodDataset.ingredients()) {
            String normalizedSeedName = foodMetadataService.normalizeToken(seed.name());
            if (existingNames.contains(normalizedSeedName) || ingredientRepository.existsByNameIgnoreCase(seed.name())) {
                existingNames.add(normalizedSeedName);
                continue;
            }

            Ingredient ingredient = new Ingredient(
                seed.name(),
                seed.category(),
                seed.cuisine(),
                seed.caloriesPer100g(),
                seed.servingNote()
            );
            foodMetadataService.applyDefaults(ingredient);

            ingredientRepository.save(ingredient);
            existingNames.add(normalizedSeedName);
        }
    }

    private void backfillIngredientMetadata() {
        ingredientRepository.findAll().forEach(ingredient -> {
            String beforeAliases = ingredient.getAliases();
            String beforeAvailability = ingredient.getRegionalAvailability();
            Double beforeProtein = ingredient.getProteinPer100g();
            Double beforeCarbs = ingredient.getCarbsPer100g();
            Double beforeFats = ingredient.getFatsPer100g();
            Double beforeFiber = ingredient.getFiberPer100g();
            Double beforePrice = ingredient.getAveragePriceUsd();
            String beforePriceUnit = ingredient.getAveragePriceUnit();
            String beforeImageUrl = ingredient.getImageUrl();

            foodMetadataService.applyDefaults(ingredient);

            boolean changed = !equalsNullable(beforeAliases, ingredient.getAliases())
                || !equalsNullable(beforeAvailability, ingredient.getRegionalAvailability())
                || !equalsNullable(beforeProtein, ingredient.getProteinPer100g())
                || !equalsNullable(beforeCarbs, ingredient.getCarbsPer100g())
                || !equalsNullable(beforeFats, ingredient.getFatsPer100g())
                || !equalsNullable(beforeFiber, ingredient.getFiberPer100g())
                || !equalsNullable(beforePrice, ingredient.getAveragePriceUsd())
                || !equalsNullable(beforePriceUnit, ingredient.getAveragePriceUnit())
                || !equalsNullable(beforeImageUrl, ingredient.getImageUrl());

            if (changed) {
                ingredientRepository.save(ingredient);
            }
        });
    }

    private boolean equalsNullable(Object left, Object right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private void seedDishes() {
        int seedCount = GlobalFoodDataset.dishes().size();
        long existingCount = dishRepository.count();
        if (existingCount >= seedCount) {
            return;
        }

        Set<String> seededNow = new HashSet<>();

        for (GlobalFoodDataset.DishSeed dishSeed : GlobalFoodDataset.dishes()) {
            String normalizedDishName = foodMetadataService.normalizeToken(dishSeed.name());
            if (seededNow.contains(normalizedDishName) || dishRepository.existsByNameIgnoreCase(dishSeed.name())) {
                seededNow.add(normalizedDishName);
                continue;
            }

            Dish dish = new Dish(dishSeed.name(), dishSeed.cuisine(), dishSeed.description());
            dish.setImageUrl(foodMetadataService.resolveDishImageUrl(null, dishSeed.name(), dishSeed.cuisine()));
            Dish savedDish = dishRepository.save(dish);
            seededNow.add(normalizedDishName);

            for (GlobalFoodDataset.DishComponentSeed componentSeed : dishSeed.components()) {
                Ingredient ingredient = ingredientRepository.findFirstByNameIgnoreCase(componentSeed.ingredientName()).orElse(null);
                if (ingredient == null || ingredient.getId() == null) {
                    continue;
                }

                DishComponent component = new DishComponent(savedDish, ingredient, componentSeed.grams());
                dishComponentRepository.save(component);
            }
        }
    }

    private void backfillDishMetadata() {
        dishRepository.findAll().forEach(dish -> {
            String resolved = foodMetadataService.resolveDishImageUrl(dish.getImageUrl(), dish.getName(), dish.getCuisine());
            if (!equalsNullable(dish.getImageUrl(), resolved)) {
                dish.setImageUrl(resolved);
                dishRepository.save(dish);
            }
        });
    }
}

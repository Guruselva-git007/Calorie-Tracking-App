package com.calorietracker.service;

import java.util.HashMap;
import java.util.Map;

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

    public DataSeeder(
        AppUserRepository appUserRepository,
        IngredientRepository ingredientRepository,
        DishRepository dishRepository,
        DishComponentRepository dishComponentRepository,
        FoodMetadataService foodMetadataService
    ) {
        this.appUserRepository = appUserRepository;
        this.ingredientRepository = ingredientRepository;
        this.dishRepository = dishRepository;
        this.dishComponentRepository = dishComponentRepository;
        this.foodMetadataService = foodMetadataService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedDefaultUser();
        seedIngredients();
        backfillIngredientMetadata();
        seedDishes();
        backfillDishMetadata();
    }

    private void seedDefaultUser() {
        if (appUserRepository.count() == 0) {
            appUserRepository.save(new AppUser("Default User", 2200));
        }
    }

    private void seedIngredients() {
        for (GlobalFoodDataset.IngredientSeed seed : GlobalFoodDataset.ingredients()) {
            if (ingredientRepository.existsByNameIgnoreCase(seed.name())) {
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
        Map<String, Ingredient> ingredientByName = new HashMap<>();
        ingredientRepository.findAll().forEach(ingredient -> ingredientByName.put(ingredient.getName(), ingredient));

        for (GlobalFoodDataset.DishSeed dishSeed : GlobalFoodDataset.dishes()) {
            if (dishRepository.existsByNameIgnoreCase(dishSeed.name())) {
                continue;
            }

            Dish dish = new Dish(dishSeed.name(), dishSeed.cuisine(), dishSeed.description());
            dish.setImageUrl(foodMetadataService.resolveDishImageUrl(null, dishSeed.name(), dishSeed.cuisine()));
            Dish savedDish = dishRepository.save(dish);

            for (GlobalFoodDataset.DishComponentSeed componentSeed : dishSeed.components()) {
                Ingredient ingredient = ingredientByName.get(componentSeed.ingredientName());
                if (ingredient == null) {
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

package com.calorietracker.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.DishResponse;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.dto.UserNutritionCustomizationRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.AuthService;
import com.calorietracker.service.UserFoodCustomizationService;

@RestController
@RequestMapping("/api/personalization")
@Validated
public class UserFoodCustomizationController {

    private final AuthService authService;
    private final UserFoodCustomizationService userFoodCustomizationService;

    public UserFoodCustomizationController(AuthService authService, UserFoodCustomizationService userFoodCustomizationService) {
        this.authService = authService;
        this.userFoodCustomizationService = userFoodCustomizationService;
    }

    @PostMapping("/ingredient/{ingredientId}")
    public IngredientResponse saveIngredientCustomization(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long ingredientId,
        @RequestBody UserNutritionCustomizationRequest request
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        return userFoodCustomizationService.upsertIngredientCustomization(user, ingredientId, request);
    }

    @DeleteMapping("/ingredient/{ingredientId}")
    public void clearIngredientCustomization(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long ingredientId
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        userFoodCustomizationService.clearIngredientCustomization(user, ingredientId);
    }

    @PostMapping("/dish/{dishId}")
    public DishResponse saveDishCustomization(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long dishId,
        @RequestBody UserNutritionCustomizationRequest request
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        return userFoodCustomizationService.upsertDishCustomization(user, dishId, request);
    }

    @DeleteMapping("/dish/{dishId}")
    public void clearDishCustomization(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long dishId
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        userFoodCustomizationService.clearDishCustomization(user, dishId);
    }
}

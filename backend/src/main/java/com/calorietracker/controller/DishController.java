package com.calorietracker.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.dto.CreateCustomDishRequest;
import com.calorietracker.dto.DishCalculationRequest;
import com.calorietracker.dto.DishResponse;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.AuthService;
import com.calorietracker.service.DishService;
import com.calorietracker.service.UserFoodCustomizationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/dishes")
@Validated
public class DishController {

    private final DishService dishService;
    private final AuthService authService;
    private final UserFoodCustomizationService userFoodCustomizationService;

    public DishController(
        DishService dishService,
        AuthService authService,
        UserFoodCustomizationService userFoodCustomizationService
    ) {
        this.dishService = dishService;
        this.authService = authService;
        this.userFoodCustomizationService = userFoodCustomizationService;
    }

    @GetMapping
    public List<DishResponse> getDishes(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String cuisine,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyDishCustomizations(user, dishService.findDishes(search, cuisine, limit));
    }

    @GetMapping("/suggest")
    public List<DishResponse> suggestDishes(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam String search,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyDishCustomizations(user, dishService.findDishSuggestions(search, limit));
    }

    @GetMapping("/{id}")
    public DishResponse getDish(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long id
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyDishCustomization(user, dishService.getDishById(id));
    }

    @PostMapping("/custom")
    public DishResponse createCustomDish(@Valid @RequestBody CreateCustomDishRequest request) {
        return dishService.createCustomDish(request);
    }

    @PostMapping("/{id}/calculate")
    public CalculationResponse calculateDish(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long id,
        @Valid @RequestBody(required = false) DishCalculationRequest request
    ) {
        DishCalculationRequest payload = request == null ? new DishCalculationRequest() : request;
        return dishService.calculateDishCalories(id, payload.getServings(), payload.getCustomIngredients());
    }

    private AppUser resolveUserIfAvailable(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            return authService.userFromTokenOrThrow(token);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

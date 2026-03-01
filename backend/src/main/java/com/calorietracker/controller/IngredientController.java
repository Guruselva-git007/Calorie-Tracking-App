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

import com.calorietracker.dto.CreateIngredientRequest;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.AuthService;
import com.calorietracker.service.IngredientService;
import com.calorietracker.service.UserFoodCustomizationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
@Validated
public class IngredientController {

    private final IngredientService ingredientService;
    private final AuthService authService;
    private final UserFoodCustomizationService userFoodCustomizationService;

    public IngredientController(
        IngredientService ingredientService,
        AuthService authService,
        UserFoodCustomizationService userFoodCustomizationService
    ) {
        this.ingredientService = ingredientService;
        this.authService = authService;
        this.userFoodCustomizationService = userFoodCustomizationService;
    }

    @GetMapping("/ingredients")
    public List<IngredientResponse> getIngredients(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String cuisine,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyIngredientCustomizations(
            user,
            ingredientService.findIngredients(search, category, cuisine, limit)
        );
    }

    @GetMapping("/ingredients/{id}")
    public IngredientResponse getIngredient(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @PathVariable Long id
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyIngredientCustomization(user, ingredientService.getIngredientById(id));
    }

    @PostMapping("/ingredients/custom")
    public IngredientResponse createIngredient(@Valid @RequestBody CreateIngredientRequest request) {
        return ingredientService.createIngredient(request);
    }

    @GetMapping("/foods")
    public List<IngredientResponse> getFoods(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam(required = false, name = "search") String search,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyIngredientCustomizations(
            user,
            ingredientService.findIngredients(search, null, null, limit)
        );
    }

    @GetMapping("/foods/search")
    public List<IngredientResponse> searchFoods(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam(name = "name") String name,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = resolveUserIfAvailable(token);
        return userFoodCustomizationService.applyIngredientCustomizations(
            user,
            ingredientService.findIngredients(name, null, null, limit)
        );
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

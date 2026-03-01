package com.calorietracker.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.DishResponse;
import com.calorietracker.dto.IngredientResponse;
import com.calorietracker.dto.UserNutritionCustomizationRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.Dish;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.PersonalizedFoodType;
import com.calorietracker.entity.UserFoodCustomization;
import com.calorietracker.repository.UserFoodCustomizationRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class UserFoodCustomizationService {

    private static final String GUEST_EMAIL = "guest@calorietracker.local";
    private static final double INR_PER_USD = 83.0;

    private final UserFoodCustomizationRepository userFoodCustomizationRepository;
    private final IngredientService ingredientService;
    private final DishService dishService;

    public UserFoodCustomizationService(
        UserFoodCustomizationRepository userFoodCustomizationRepository,
        IngredientService ingredientService,
        DishService dishService
    ) {
        this.userFoodCustomizationRepository = userFoodCustomizationRepository;
        this.ingredientService = ingredientService;
        this.dishService = dishService;
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> applyIngredientCustomizations(AppUser user, List<IngredientResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        if (!canApplyForUser(user)) {
            items.forEach(item -> item.setPersonalized(false));
            return items;
        }

        Map<Long, UserFoodCustomization> customizationByFoodId = loadCustomizationMap(
            user.getId(),
            PersonalizedFoodType.INGREDIENT,
            items.stream().map(IngredientResponse::getId).collect(Collectors.toSet())
        );

        items.forEach(item -> {
            UserFoodCustomization customization = customizationByFoodId.get(item.getId());
            applyIngredientCustomization(item, customization);
        });
        return items;
    }

    @Transactional(readOnly = true)
    public IngredientResponse applyIngredientCustomization(AppUser user, IngredientResponse item) {
        if (item == null) {
            return null;
        }

        if (!canApplyForUser(user)) {
            item.setPersonalized(false);
            return item;
        }

        Optional<UserFoodCustomization> customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.INGREDIENT, item.getId());
        applyIngredientCustomization(item, customization.orElse(null));
        return item;
    }

    @Transactional(readOnly = true)
    public List<DishResponse> applyDishCustomizations(AppUser user, List<DishResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        if (!canApplyForUser(user)) {
            items.forEach(item -> item.setPersonalized(false));
            return items;
        }

        Map<Long, UserFoodCustomization> customizationByFoodId = loadCustomizationMap(
            user.getId(),
            PersonalizedFoodType.DISH,
            items.stream().map(DishResponse::getId).collect(Collectors.toSet())
        );

        items.forEach(item -> {
            UserFoodCustomization customization = customizationByFoodId.get(item.getId());
            applyDishCustomization(item, customization);
        });
        return items;
    }

    @Transactional(readOnly = true)
    public DishResponse applyDishCustomization(AppUser user, DishResponse item) {
        if (item == null) {
            return null;
        }

        if (!canApplyForUser(user)) {
            item.setPersonalized(false);
            return item;
        }

        Optional<UserFoodCustomization> customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.DISH, item.getId());
        applyDishCustomization(item, customization.orElse(null));
        return item;
    }

    @Transactional
    public IngredientResponse upsertIngredientCustomization(AppUser user, Long ingredientId, UserNutritionCustomizationRequest request) {
        ensureSignedInUser(user);
        validateCustomizationRequest(request);

        Ingredient ingredient = ingredientService.getIngredientOrThrow(ingredientId);
        UserFoodCustomization customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.INGREDIENT, ingredientId)
            .orElseGet(UserFoodCustomization::new);

        customization.setUser(user);
        customization.setFoodType(PersonalizedFoodType.INGREDIENT);
        customization.setFoodId(ingredientId);
        customization.setFoodName(ingredient.getName());
        applyRequestToCustomization(customization, request);

        userFoodCustomizationRepository.save(customization);
        IngredientResponse response = ingredientService.getIngredientById(ingredientId);
        applyIngredientCustomization(response, customization);
        return response;
    }

    @Transactional
    public DishResponse upsertDishCustomization(AppUser user, Long dishId, UserNutritionCustomizationRequest request) {
        ensureSignedInUser(user);
        validateCustomizationRequest(request);

        Dish dish = dishService.getDishOrThrow(dishId);
        UserFoodCustomization customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.DISH, dishId)
            .orElseGet(UserFoodCustomization::new);

        customization.setUser(user);
        customization.setFoodType(PersonalizedFoodType.DISH);
        customization.setFoodId(dishId);
        customization.setFoodName(dish.getName());
        applyRequestToCustomization(customization, request);

        userFoodCustomizationRepository.save(customization);
        DishResponse response = dishService.getDishById(dishId);
        applyDishCustomization(response, customization);
        return response;
    }

    @Transactional
    public void clearIngredientCustomization(AppUser user, Long ingredientId) {
        ensureSignedInUser(user);
        userFoodCustomizationRepository.deleteByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.INGREDIENT, ingredientId);
    }

    @Transactional
    public void clearDishCustomization(AppUser user, Long dishId) {
        ensureSignedInUser(user);
        userFoodCustomizationRepository.deleteByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.DISH, dishId);
    }

    @Transactional(readOnly = true)
    public NutritionMetrics resolveIngredientMetrics(AppUser user, Ingredient ingredient) {
        NutritionMetrics base = new NutritionMetrics(
            ingredient.getCaloriesPer100g(),
            ingredient.getProteinPer100g(),
            ingredient.getCarbsPer100g(),
            ingredient.getFatsPer100g(),
            ingredient.getFiberPer100g(),
            ingredient.getAveragePriceUsd(),
            false
        );

        if (!canApplyForUser(user) || ingredient == null || ingredient.getId() == null) {
            return base;
        }

        UserFoodCustomization customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.INGREDIENT, ingredient.getId())
            .orElse(null);
        return mergeMetrics(base, customization);
    }

    @Transactional(readOnly = true)
    public NutritionMetrics resolveDishMetrics(AppUser user, Dish dish, NutritionMetrics fallback) {
        NutritionMetrics safeFallback = fallback == null ? new NutritionMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false) : fallback;
        if (!canApplyForUser(user) || dish == null || dish.getId() == null) {
            return safeFallback;
        }

        UserFoodCustomization customization = userFoodCustomizationRepository
            .findFirstByUserIdAndFoodTypeAndFoodId(user.getId(), PersonalizedFoodType.DISH, dish.getId())
            .orElse(null);
        return mergeMetrics(safeFallback, customization);
    }

    private NutritionMetrics mergeMetrics(NutritionMetrics base, UserFoodCustomization customization) {
        if (customization == null) {
            return base;
        }

        return new NutritionMetrics(
            customization.getCaloriesValue() == null ? base.calories() : customization.getCaloriesValue(),
            customization.getProteinValue() == null ? base.protein() : customization.getProteinValue(),
            customization.getCarbsValue() == null ? base.carbs() : customization.getCarbsValue(),
            customization.getFatsValue() == null ? base.fats() : customization.getFatsValue(),
            customization.getFiberValue() == null ? base.fiber() : customization.getFiberValue(),
            customization.getPriceUsdValue() == null ? base.priceUsd() : customization.getPriceUsdValue(),
            true
        );
    }

    private void applyIngredientCustomization(IngredientResponse response, UserFoodCustomization customization) {
        if (response == null) {
            return;
        }

        if (customization == null) {
            response.setPersonalized(false);
            return;
        }

        if (customization.getCaloriesValue() != null) {
            response.setCaloriesPer100g(customization.getCaloriesValue());
        }
        if (customization.getProteinValue() != null) {
            response.setProteinPer100g(customization.getProteinValue());
        }
        if (customization.getCarbsValue() != null) {
            response.setCarbsPer100g(customization.getCarbsValue());
        }
        if (customization.getFatsValue() != null) {
            response.setFatsPer100g(customization.getFatsValue());
        }
        if (customization.getFiberValue() != null) {
            response.setFiberPer100g(customization.getFiberValue());
        }
        if (customization.getPriceUsdValue() != null) {
            response.setAveragePriceUsd(customization.getPriceUsdValue());
        }
        response.setPersonalized(true);
    }

    private void applyDishCustomization(DishResponse response, UserFoodCustomization customization) {
        if (response == null) {
            return;
        }

        if (customization == null) {
            response.setPersonalized(false);
            return;
        }

        if (customization.getCaloriesValue() != null) {
            response.setCaloriesPerServing(customization.getCaloriesValue());
        }
        if (customization.getProteinValue() != null) {
            response.setProteinPerServing(customization.getProteinValue());
        }
        if (customization.getCarbsValue() != null) {
            response.setCarbsPerServing(customization.getCarbsValue());
        }
        if (customization.getFatsValue() != null) {
            response.setFatsPerServing(customization.getFatsValue());
        }
        if (customization.getFiberValue() != null) {
            response.setFiberPerServing(customization.getFiberValue());
        }
        if (customization.getPriceUsdValue() != null) {
            response.setEstimatedPriceUsdPerServing(customization.getPriceUsdValue());
        }
        response.setPersonalized(true);
    }

    private Map<Long, UserFoodCustomization> loadCustomizationMap(
        Long userId,
        PersonalizedFoodType type,
        Collection<Long> foodIds
    ) {
        if (userId == null || foodIds == null || foodIds.isEmpty()) {
            return Map.of();
        }

        return userFoodCustomizationRepository.findByUserIdAndFoodTypeAndFoodIdIn(userId, type, foodIds).stream()
            .collect(Collectors.toMap(UserFoodCustomization::getFoodId, value -> value, (left, right) -> right, HashMap::new));
    }

    private void applyRequestToCustomization(UserFoodCustomization customization, UserNutritionCustomizationRequest request) {
        customization.setCaloriesValue(roundOrNull(request.getCalories()));
        customization.setProteinValue(roundOrNull(request.getProtein()));
        customization.setCarbsValue(roundOrNull(request.getCarbs()));
        customization.setFatsValue(roundOrNull(request.getFats()));
        customization.setFiberValue(roundOrNull(request.getFiber()));
        customization.setPriceUsdValue(convertInrToUsd(roundOrNull(request.getPriceInr())));
    }

    private Double convertInrToUsd(Double priceInr) {
        if (priceInr == null) {
            return null;
        }
        if (priceInr <= 0) {
            return 0.0;
        }
        return CalorieMath.round(priceInr / INR_PER_USD);
    }

    private Double roundOrNull(Double value) {
        if (value == null) {
            return null;
        }
        return CalorieMath.round(value);
    }

    private void validateCustomizationRequest(UserNutritionCustomizationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Customization payload is required.");
        }

        if (request.getCalories() == null
            && request.getProtein() == null
            && request.getCarbs() == null
            && request.getFats() == null
            && request.getFiber() == null
            && request.getPriceInr() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Provide at least one nutrition value.");
        }

        validateNonNegative("Calories", request.getCalories());
        validateNonNegative("Protein", request.getProtein());
        validateNonNegative("Carbs", request.getCarbs());
        validateNonNegative("Fats", request.getFats());
        validateNonNegative("Fiber", request.getFiber());
        validateNonNegative("Price", request.getPriceInr());

        if (request.getCalories() != null && request.getCalories() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Calories must be greater than 0.");
        }
    }

    private void validateNonNegative(String label, Double value) {
        if (value == null) {
            return;
        }
        if (value < 0) {
            throw new ResponseStatusException(BAD_REQUEST, label + " cannot be negative.");
        }
    }

    private boolean canApplyForUser(AppUser user) {
        return user != null && !isGuestUser(user);
    }

    private void ensureSignedInUser(AppUser user) {
        if (user == null || isGuestUser(user)) {
            throw new ResponseStatusException(FORBIDDEN, "Sign in with your account to use personal nutrition customization.");
        }
    }

    private boolean isGuestUser(AppUser user) {
        if (user == null) {
            return true;
        }
        return StringUtils.hasText(user.getEmail())
            && user.getEmail().trim().toLowerCase(Locale.ROOT).equals(GUEST_EMAIL);
    }

    public record NutritionMetrics(
        Double calories,
        Double protein,
        Double carbs,
        Double fats,
        Double fiber,
        Double priceUsd,
        boolean personalized
    ) {
    }
}

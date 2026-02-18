package com.calorietracker.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.CalculationResponse;
import com.calorietracker.dto.CreateDishEntryRequest;
import com.calorietracker.dto.CreateIngredientEntryRequest;
import com.calorietracker.dto.EntryResponse;
import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.CalorieEntry;
import com.calorietracker.entity.Dish;
import com.calorietracker.entity.EntryType;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.repository.CalorieEntryRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class EntryService {

    private static final Set<String> SUPPORTED_UNITS = Set.of("g", "kg", "ml", "l");

    private final CalorieEntryRepository calorieEntryRepository;
    private final UserService userService;
    private final IngredientService ingredientService;
    private final DishService dishService;

    public EntryService(
        CalorieEntryRepository calorieEntryRepository,
        UserService userService,
        IngredientService ingredientService,
        DishService dishService
    ) {
        this.calorieEntryRepository = calorieEntryRepository;
        this.userService = userService;
        this.ingredientService = ingredientService;
        this.dishService = dishService;
    }

    @Transactional(readOnly = true)
    public List<EntryResponse> getEntries(Long userId) {
        return calorieEntryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EntryResponse> getEntriesByDate(Long userId, LocalDate date) {
        return calorieEntryRepository.findByUserIdAndEntryDateOrderByCreatedAtDesc(userId, date).stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public EntryResponse createIngredientEntry(CreateIngredientEntryRequest request) {
        AppUser user = userService.getUserOrThrow(request.getUserId());
        Ingredient ingredient = ingredientService.getIngredientOrThrow(request.getIngredientId());
        double quantity = request.getQuantity() == null ? 0.0 : request.getQuantity();
        String unit = request.getUnit() == null ? "g" : request.getUnit().trim().toLowerCase();

        if (!SUPPORTED_UNITS.contains(unit)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported unit. Use g, kg, ml, or l.");
        }

        double grams;
        if (request.getGrams() != null && request.getGrams() > 0) {
            grams = request.getGrams();
            if (quantity <= 0) {
                quantity = grams;
                unit = "g";
            }
        } else {
            if (quantity <= 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Quantity must be greater than 0.");
            }
            grams = CalorieMath.toGrams(quantity, unit);
        }

        CalorieEntry entry = new CalorieEntry();
        entry.setUser(user);
        entry.setIngredient(ingredient);
        entry.setType(EntryType.INGREDIENT);
        entry.setDisplayName(ingredient.getName());
        entry.setQuantity(CalorieMath.round(quantity));
        entry.setQuantityUnit(unit);
        entry.setTotalCalories(CalorieMath.caloriesFromGrams(ingredient.getCaloriesPer100g(), grams));
        entry.setEntryDate(request.getEntryDate() == null ? LocalDate.now() : request.getEntryDate());

        return toResponse(calorieEntryRepository.save(entry));
    }

    @Transactional
    public EntryResponse createDishEntry(CreateDishEntryRequest request) {
        AppUser user = userService.getUserOrThrow(request.getUserId());
        Dish dish = dishService.getDishOrThrow(request.getDishId());

        CalculationResponse calculation = dishService.calculateDishCalories(
            request.getDishId(),
            request.getServings(),
            request.getCustomIngredients()
        );

        boolean isCustom = request.getCustomIngredients() != null && !request.getCustomIngredients().isEmpty();

        CalorieEntry entry = new CalorieEntry();
        entry.setUser(user);
        entry.setDish(dish);
        entry.setType(isCustom ? EntryType.CUSTOM_DISH : EntryType.DISH);
        entry.setDisplayName(isCustom ? dish.getName() + " (custom)" : dish.getName());
        entry.setQuantity(CalorieMath.round(request.getServings()));
        entry.setQuantityUnit("servings");
        entry.setTotalCalories(calculation.getTotalCalories());
        entry.setEntryDate(request.getEntryDate() == null ? LocalDate.now() : request.getEntryDate());
        entry.setNote(request.getNote());

        return toResponse(calorieEntryRepository.save(entry));
    }

    @Transactional
    public void deleteEntry(Long id) {
        CalorieEntry entry = calorieEntryRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Entry not found: " + id));
        calorieEntryRepository.delete(entry);
    }

    @Transactional(readOnly = true)
    public Double getTotalCaloriesForDate(Long userId, LocalDate date) {
        return CalorieMath.round(
            calorieEntryRepository.findByUserIdAndEntryDateOrderByCreatedAtDesc(userId, date)
                .stream()
                .mapToDouble(CalorieEntry::getTotalCalories)
                .sum()
        );
    }

    private EntryResponse toResponse(CalorieEntry entry) {
        EntryResponse response = new EntryResponse();
        response.setId(entry.getId());
        response.setType(entry.getType());
        response.setDisplayName(entry.getDisplayName());
        response.setQuantity(entry.getQuantity());
        response.setQuantityUnit(entry.getQuantityUnit());
        response.setTotalCalories(entry.getTotalCalories());
        response.setEntryDate(entry.getEntryDate());
        response.setNote(entry.getNote());
        response.setCreatedAt(entry.getCreatedAt());
        return response;
    }
}

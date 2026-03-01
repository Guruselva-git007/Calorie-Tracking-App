package com.calorietracker.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Set<String> SUPPORTED_UNITS = Set.of("g", "kg", "ml", "l", "serving", "servings", "count", "counts");
    private static final Pattern SERVING_NOTE_GRAMS_PATTERN =
        Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(kg|g|grams?|ml|l|liters?|litres?)\\b", Pattern.CASE_INSENSITIVE);
    private static final double SERVING_GRAMS_FALLBACK = 100.0;

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
            throw new ResponseStatusException(BAD_REQUEST, "Unsupported unit. Use g, kg, ml, l, serving, or count.");
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
            grams = toIngredientGrams(ingredient, quantity, unit);
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

    @Transactional(readOnly = true)
    public MacroTotals getMacroTotalsForDate(Long userId, LocalDate date) {
        List<CalorieEntry> entries = calorieEntryRepository.findByUserIdAndEntryDateOrderByCreatedAtDesc(userId, date);

        double protein = 0.0;
        double carbs = 0.0;
        double fats = 0.0;
        double fiber = 0.0;

        for (CalorieEntry entry : entries) {
            Ingredient ingredient = entry.getIngredient();
            if (ingredient != null) {
                double grams = toEntryIngredientGrams(ingredient, entry.getQuantity(), entry.getQuantityUnit());
                if (grams <= 0) {
                    continue;
                }
                double factor = grams / 100.0;
                protein += valueOrZero(ingredient.getProteinPer100g()) * factor;
                carbs += valueOrZero(ingredient.getCarbsPer100g()) * factor;
                fats += valueOrZero(ingredient.getFatsPer100g()) * factor;
                fiber += valueOrZero(ingredient.getFiberPer100g()) * factor;
                continue;
            }

            Dish dish = entry.getDish();
            if (dish != null) {
                double servings = valueOrZero(entry.getQuantity());
                if (servings <= 0) {
                    servings = 1.0;
                }
                try {
                    CalculationResponse dishCalculation = dishService.calculateDishCalories(dish.getId(), servings, null);
                    protein += valueOrZero(dishCalculation.getTotalProtein());
                    carbs += valueOrZero(dishCalculation.getTotalCarbs());
                    fats += valueOrZero(dishCalculation.getTotalFats());
                    fiber += valueOrZero(dishCalculation.getTotalFiber());
                } catch (RuntimeException exception) {
                    // Keep summary resilient even if a dish was removed or cannot be recalculated.
                }
            }
        }

        return new MacroTotals(
            CalorieMath.round(protein),
            CalorieMath.round(carbs),
            CalorieMath.round(fats),
            CalorieMath.round(fiber)
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

    private double toEntryIngredientGrams(Ingredient ingredient, Double quantity, String unit) {
        double safeQuantity = valueOrZero(quantity);
        if (safeQuantity <= 0) {
            return 0.0;
        }

        String normalizedUnit = unit == null ? "g" : unit.trim().toLowerCase();
        if (!SUPPORTED_UNITS.contains(normalizedUnit)) {
            normalizedUnit = "g";
        }

        return toIngredientGrams(ingredient, safeQuantity, normalizedUnit);
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private double toIngredientGrams(Ingredient ingredient, double quantity, String unit) {
        if ("serving".equals(unit) || "servings".equals(unit) || "count".equals(unit) || "counts".equals(unit)) {
            double servingSize = resolveServingSizeInGrams(ingredient);
            return CalorieMath.round(quantity * servingSize);
        }
        return CalorieMath.toGrams(quantity, unit);
    }

    private double resolveServingSizeInGrams(Ingredient ingredient) {
        String servingNote = ingredient == null ? null : ingredient.getServingNote();
        if (servingNote == null || servingNote.isBlank()) {
            return SERVING_GRAMS_FALLBACK;
        }

        Matcher matcher = SERVING_NOTE_GRAMS_PATTERN.matcher(servingNote);
        if (!matcher.find()) {
            return SERVING_GRAMS_FALLBACK;
        }

        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException exception) {
            return SERVING_GRAMS_FALLBACK;
        }

        if (value <= 0) {
            return SERVING_GRAMS_FALLBACK;
        }

        String parsedUnit = matcher.group(2).trim().toLowerCase();
        if ("kg".equals(parsedUnit) || "l".equals(parsedUnit) || "liter".equals(parsedUnit) || "liters".equals(parsedUnit) || "litre".equals(parsedUnit) || "litres".equals(parsedUnit)) {
            return value * 1000.0;
        }
        return value;
    }

    public static class MacroTotals {
        private final double protein;
        private final double carbs;
        private final double fats;
        private final double fiber;

        public MacroTotals(double protein, double carbs, double fats, double fiber) {
            this.protein = protein;
            this.carbs = carbs;
            this.fats = fats;
            this.fiber = fiber;
        }

        public double getProtein() {
            return protein;
        }

        public double getCarbs() {
            return carbs;
        }

        public double getFats() {
            return fats;
        }

        public double getFiber() {
            return fiber;
        }
    }
}

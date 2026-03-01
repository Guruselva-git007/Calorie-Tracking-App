package com.calorietracker.service;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.Dish;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.repository.DishRepository;
import com.calorietracker.repository.IngredientRepository;

@Service
public class DatasetCorrectionService {

    private final IngredientRepository ingredientRepository;
    private final DishRepository dishRepository;
    private final FoodMetadataService foodMetadataService;

    public DatasetCorrectionService(
        IngredientRepository ingredientRepository,
        DishRepository dishRepository,
        FoodMetadataService foodMetadataService
    ) {
        this.ingredientRepository = ingredientRepository;
        this.dishRepository = dishRepository;
        this.foodMetadataService = foodMetadataService;
    }

    @Transactional
    public Map<String, Object> correctAllDatasets(boolean promoteFactChecked) {
        int ingredientsScanned = 0;
        int ingredientsUpdated = 0;
        int ingredientNameNormalized = 0;
        int ingredientCuisineNormalized = 0;
        int ingredientServingNoteNormalized = 0;
        int ingredientAliasesNormalized = 0;
        int ingredientAvailabilityNormalized = 0;
        int ingredientFactCheckedPromoted = 0;
        int ingredientSourceNormalized = 0;
        int dishScanned = 0;
        int dishesUpdated = 0;
        int dishNameNormalized = 0;
        int dishCuisineNormalized = 0;
        int dishDescriptionNormalized = 0;
        int dishFactCheckedPromoted = 0;
        int dishSourceNormalized = 0;

        List<Ingredient> ingredients = ingredientRepository.findAll();
        Set<String> ingredientNameIndex = new HashSet<>();
        for (Ingredient ingredient : ingredients) {
            ingredientNameIndex.add(normalizeExactNameKey(ingredient.getName()));
        }

        for (Ingredient ingredient : ingredients) {
            ingredientsScanned++;

            String beforeName = ingredient.getName();
            String beforeCuisine = ingredient.getCuisine();
            String beforeSource = ingredient.getSource();
            Boolean beforeFactChecked = ingredient.getFactChecked();
            String beforeAliases = ingredient.getAliases();
            String beforeAvailability = ingredient.getRegionalAvailability();
            Double beforeProtein = ingredient.getProteinPer100g();
            Double beforeCarbs = ingredient.getCarbsPer100g();
            Double beforeFats = ingredient.getFatsPer100g();
            Double beforeFiber = ingredient.getFiberPer100g();
            Double beforePrice = ingredient.getAveragePriceUsd();
            String beforePriceUnit = ingredient.getAveragePriceUnit();
            String beforeImageUrl = ingredient.getImageUrl();
            String beforeServingNote = ingredient.getServingNote();

            String normalizedName = normalizeEntityName(beforeName, 180);
            String oldNameKey = normalizeExactNameKey(beforeName);
            String newNameKey = normalizeExactNameKey(normalizedName);
            if (StringUtils.hasText(normalizedName) && !equalsNullable(beforeName, normalizedName)) {
                if (oldNameKey.equals(newNameKey) || !ingredientNameIndex.contains(newNameKey)) {
                    ingredient.setName(normalizedName);
                    ingredientNameIndex.remove(oldNameKey);
                    ingredientNameIndex.add(newNameKey);
                    ingredientNameNormalized++;
                }
            }

            String normalizedCuisine = normalizeCuisine(ingredient.getCuisine(), 120);
            if (!equalsNullable(ingredient.getCuisine(), normalizedCuisine)) {
                ingredient.setCuisine(normalizedCuisine);
                ingredientCuisineNormalized++;
            }

            String normalizedServingNote = normalizeServingNote(ingredient.getServingNote());
            if (!equalsNullable(ingredient.getServingNote(), normalizedServingNote)) {
                ingredient.setServingNote(normalizedServingNote);
                ingredientServingNoteNormalized++;
            }

            String normalizedAliases = mergeAndNormalizeAliases(ingredient.getAliases(), ingredient.getName());
            if (!equalsNullable(ingredient.getAliases(), normalizedAliases)) {
                ingredient.setAliases(normalizedAliases);
                ingredientAliasesNormalized++;
            }

            String normalizedAvailability = mergeAndNormalizeAvailability(
                ingredient.getRegionalAvailability(),
                ingredient.getCuisine()
            );
            if (!equalsNullable(ingredient.getRegionalAvailability(), normalizedAvailability)) {
                ingredient.setRegionalAvailability(normalizedAvailability);
                ingredientAvailabilityNormalized++;
            }

            String normalizedSource = normalizeSource(beforeSource, "CURATED");
            if (!equalsNullable(beforeSource, normalizedSource)) {
                ingredient.setSource(normalizedSource);
                ingredientSourceNormalized++;
            }

            foodMetadataService.applyDefaults(ingredient);

            if (promoteFactChecked && shouldPromoteIngredientFactCheck(ingredient.getSource())) {
                if (!Boolean.TRUE.equals(ingredient.getFactChecked())) {
                    ingredient.setFactChecked(true);
                    ingredientFactCheckedPromoted++;
                }
            }

            boolean changed = !equalsNullable(beforeName, ingredient.getName())
                || !equalsNullable(beforeCuisine, ingredient.getCuisine())
                || !equalsNullable(beforeSource, ingredient.getSource())
                || !equalsNullable(beforeFactChecked, ingredient.getFactChecked())
                || !equalsNullable(beforeAliases, ingredient.getAliases())
                || !equalsNullable(beforeAvailability, ingredient.getRegionalAvailability())
                || !equalsNullable(beforeProtein, ingredient.getProteinPer100g())
                || !equalsNullable(beforeCarbs, ingredient.getCarbsPer100g())
                || !equalsNullable(beforeFats, ingredient.getFatsPer100g())
                || !equalsNullable(beforeFiber, ingredient.getFiberPer100g())
                || !equalsNullable(beforePrice, ingredient.getAveragePriceUsd())
                || !equalsNullable(beforePriceUnit, ingredient.getAveragePriceUnit())
                || !equalsNullable(beforeImageUrl, ingredient.getImageUrl())
                || !equalsNullable(beforeServingNote, ingredient.getServingNote());

            if (changed) {
                ingredientRepository.save(ingredient);
                ingredientsUpdated++;
            }
        }

        List<Dish> dishes = dishRepository.findAll();
        Set<String> dishNameIndex = new HashSet<>();
        for (Dish dish : dishes) {
            dishNameIndex.add(normalizeExactNameKey(dish.getName()));
        }

        for (Dish dish : dishes) {
            dishScanned++;

            String beforeName = dish.getName();
            String beforeCuisine = dish.getCuisine();
            String beforeSource = dish.getSource();
            Boolean beforeFactChecked = dish.getFactChecked();
            String beforeDescription = dish.getDescription();
            String beforeImageUrl = dish.getImageUrl();

            String normalizedName = normalizeEntityName(beforeName, 180);
            String oldNameKey = normalizeExactNameKey(beforeName);
            String newNameKey = normalizeExactNameKey(normalizedName);
            if (StringUtils.hasText(normalizedName) && !equalsNullable(beforeName, normalizedName)) {
                if (oldNameKey.equals(newNameKey) || !dishNameIndex.contains(newNameKey)) {
                    dish.setName(normalizedName);
                    dishNameIndex.remove(oldNameKey);
                    dishNameIndex.add(newNameKey);
                    dishNameNormalized++;
                }
            }

            String normalizedCuisine = normalizeCuisine(dish.getCuisine(), 120);
            if (!equalsNullable(dish.getCuisine(), normalizedCuisine)) {
                dish.setCuisine(normalizedCuisine);
                dishCuisineNormalized++;
            }

            String normalizedSource = normalizeSource(beforeSource, "CURATED");
            if (!equalsNullable(beforeSource, normalizedSource)) {
                dish.setSource(normalizedSource);
                dishSourceNormalized++;
            }

            String normalizedDescription = normalizeDescription(dish.getDescription());
            if (!equalsNullable(dish.getDescription(), normalizedDescription)) {
                dish.setDescription(normalizedDescription);
                dishDescriptionNormalized++;
            }

            String resolvedImageUrl = foodMetadataService.resolveDishImageUrl(
                dish.getImageUrl(),
                dish.getName(),
                dish.getCuisine()
            );
            if (!equalsNullable(dish.getImageUrl(), resolvedImageUrl)) {
                dish.setImageUrl(resolvedImageUrl);
            }

            if (promoteFactChecked && shouldPromoteDishFactCheck(dish.getSource())) {
                if (!Boolean.TRUE.equals(dish.getFactChecked())) {
                    dish.setFactChecked(true);
                    dishFactCheckedPromoted++;
                }
            }

            boolean changed = !equalsNullable(beforeName, dish.getName())
                || !equalsNullable(beforeCuisine, dish.getCuisine())
                || !equalsNullable(beforeSource, dish.getSource())
                || !equalsNullable(beforeFactChecked, dish.getFactChecked())
                || !equalsNullable(beforeDescription, dish.getDescription())
                || !equalsNullable(beforeImageUrl, dish.getImageUrl());

            if (changed) {
                dishRepository.save(dish);
                dishesUpdated++;
            }
        }

        Map<String, Integer> duplicateSummary = findNearDuplicateIngredientNames();
        List<String> duplicateExamples = duplicateSummary.entrySet().stream()
            .limit(20)
            .map(entry -> entry.getKey() + " x" + entry.getValue())
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("source", "Dataset Correction");
        result.put("ingredientsScanned", ingredientsScanned);
        result.put("ingredientsUpdated", ingredientsUpdated);
        result.put("ingredientNameNormalized", ingredientNameNormalized);
        result.put("ingredientCuisineNormalized", ingredientCuisineNormalized);
        result.put("ingredientServingNoteNormalized", ingredientServingNoteNormalized);
        result.put("ingredientAliasesNormalized", ingredientAliasesNormalized);
        result.put("ingredientAvailabilityNormalized", ingredientAvailabilityNormalized);
        result.put("ingredientFactCheckedPromoted", ingredientFactCheckedPromoted);
        result.put("ingredientSourceNormalized", ingredientSourceNormalized);
        result.put("dishesScanned", dishScanned);
        result.put("dishesUpdated", dishesUpdated);
        result.put("dishNameNormalized", dishNameNormalized);
        result.put("dishCuisineNormalized", dishCuisineNormalized);
        result.put("dishDescriptionNormalized", dishDescriptionNormalized);
        result.put("dishFactCheckedPromoted", dishFactCheckedPromoted);
        result.put("dishSourceNormalized", dishSourceNormalized);
        result.put("nearDuplicateIngredientNames", duplicateSummary.size());
        result.put("nearDuplicateExamples", duplicateExamples);
        return result;
    }

    private Map<String, Integer> findNearDuplicateIngredientNames() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredientRepository.findAll()) {
            String normalized = foodMetadataService.normalizeToken(ingredient.getName());
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            counts.put(normalized, counts.getOrDefault(normalized, 0) + 1);
        }

        Map<String, Integer> duplicates = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }
        return duplicates;
    }

    private boolean shouldPromoteIngredientFactCheck(String source) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        Set<String> accepted = Set.of(
            "CURATED",
            "CURATED_DESSERT_REFERENCE",
            "OPEN_FOOD_FACTS_DESSERT",
            "USDA_CORGIS_DESSERTS",
            "USER_CONFIRMED"
        );
        return accepted.contains(normalized);
    }

    private boolean shouldPromoteDishFactCheck(String source) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        Set<String> accepted = Set.of(
            "CURATED",
            "THE_MEAL_DB_DESSERT",
            "USER_CONFIRMED"
        );
        return accepted.contains(normalized);
    }

    private String normalizeSource(String source, String fallback) {
        String candidate = StringUtils.hasText(source) ? source.trim() : fallback;
        if ("CURATED_DESSERT_FALLBACK".equalsIgnoreCase(candidate)) {
            candidate = "CURATED_DESSERT_REFERENCE";
        }

        candidate = candidate
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");

        if (!StringUtils.hasText(candidate)) {
            candidate = fallback;
        }
        if (candidate.length() > 50) {
            candidate = candidate.substring(0, 50);
        }
        return candidate;
    }

    private String normalizeNameKey(String value) {
        return foodMetadataService.normalizeToken(value);
    }

    private String normalizeExactNameKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEntityName(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        String cleaned = sanitizeText(value)
            .replaceAll("(^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$)", "")
            .replaceAll("\\s+", " ")
            .trim();

        if (!StringUtils.hasText(cleaned)) {
            return "";
        }

        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength).trim();
        }
        return cleaned;
    }

    private String normalizeCuisine(String value, int maxLength) {
        String cleaned = sanitizeText(value)
            .replace("_", " ")
            .replace("-", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (!StringUtils.hasText(cleaned)) {
            return "Global";
        }

        String[] parts = cleaned.split(" ");
        List<String> titled = new ArrayList<>();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            titled.add(lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1));
        }

        String normalized = String.join(" ", titled).trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = "Global";
        }
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).trim();
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        String normalized = sanitizeText(value)
            .replaceAll("\\s+", " ")
            .trim();

        if (!StringUtils.hasText(normalized)) {
            normalized = "Imported dish";
        }
        if (normalized.length() > 500) {
            normalized = normalized.substring(0, 500).trim();
        }
        return normalized;
    }

    private String normalizeServingNote(String value) {
        String normalized = sanitizeText(value)
            .toLowerCase(Locale.ROOT)
            .replace("grams", "g")
            .replace("gram", "g")
            .replace("kilograms", "kg")
            .replace("kilogram", "kg")
            .replace("milliliters", "ml")
            .replace("milliliter", "ml")
            .replace("liters", "l")
            .replace("liter", "l")
            .replace("servings", "serving")
            .replace("counts", "count")
            .replaceAll("\\s+", " ")
            .trim();

        if (!StringUtils.hasText(normalized)) {
            return "100 g";
        }

        String compact = normalized.replace(" ", "");
        if (compact.matches("^\\d+(\\.\\d+)?(g|kg|ml|l|oz|lb|serving|count)$")) {
            String quantity = compact.replaceAll("(g|kg|ml|l|oz|lb|serving|count)$", "");
            String unit = compact.substring(quantity.length());
            return formatServingQuantity(quantity) + " " + unit;
        }

        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80).trim();
        }
        return normalized;
    }

    private String mergeAndNormalizeAliases(String aliasesCsv, String currentName) {
        Set<String> merged = new LinkedHashSet<>(foodMetadataService.fromCsv(aliasesCsv));
        merged.addAll(foodMetadataService.buildAliases(currentName));

        String normalizedNameKey = normalizeNameKey(currentName);
        List<String> cleaned = merged.stream()
            .map(value -> normalizeEntityName(value, 70))
            .filter(StringUtils::hasText)
            .filter(value -> !normalizeNameKey(value).equals(normalizedNameKey))
            .toList();

        return foodMetadataService.toCsv(capListByLength(cleaned, 24, 580));
    }

    private String mergeAndNormalizeAvailability(String availabilityCsv, String cuisine) {
        Set<String> merged = new LinkedHashSet<>(foodMetadataService.fromCsv(availabilityCsv));
        merged.addAll(foodMetadataService.buildRegionalAvailability(cuisine));

        List<String> cleaned = merged.stream()
            .map(value -> normalizeCuisine(value, 40))
            .filter(StringUtils::hasText)
            .toList();

        return foodMetadataService.toCsv(capListByLength(cleaned, 16, 380));
    }

    private String sanitizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value
            .replace("&quot;", " ")
            .replace("&#34;", " ")
            .replace("&amp;", " and ")
            .replace("&#38;", " and ")
            .replace("&nbsp;", " ")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", " ")
            .replace("&gt;", " ")
            .replaceAll("[\\p{Cntrl}]", " ")
            .trim();
    }

    private String formatServingQuantity(String raw) {
        try {
            double value = Double.parseDouble(raw);
            DecimalFormat formatter = new DecimalFormat("0.##");
            return formatter.format(value);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private List<String> capListByLength(List<String> values, int maxItems, int maxChars) {
        if (values == null || values.isEmpty() || maxItems <= 0 || maxChars <= 0) {
            return List.of();
        }

        List<String> capped = new ArrayList<>();
        int usedChars = 0;
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            if (capped.size() >= maxItems) {
                break;
            }

            String token = value.trim();
            int projected = usedChars == 0 ? token.length() : usedChars + 2 + token.length();
            if (projected > maxChars) {
                break;
            }

            capped.add(token);
            usedChars = projected;
        }
        return capped;
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
}

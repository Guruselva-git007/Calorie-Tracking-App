package com.calorietracker.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.DeficiencyRecommendationItem;
import com.calorietracker.dto.DeficiencyToolsRequest;
import com.calorietracker.dto.DeficiencyToolsResponse;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.IngredientRepository;

@Service
public class ToolsRecommendationService {

    private static final int MAX_FOOD_RECOMMENDATIONS = 26;

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final Map<String, DeficiencyProfile> deficiencyProfiles = new LinkedHashMap<>();
    private final Map<String, ConditionProfile> conditionProfiles = new LinkedHashMap<>();

    public ToolsRecommendationService(IngredientRepository ingredientRepository, FoodMetadataService foodMetadataService) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
        seedDeficiencyProfiles();
        seedConditionProfiles();
    }

    public DeficiencyToolsResponse buildRecommendations(DeficiencyToolsRequest request) {
        List<String> deficiencyInputs = request.getDeficiencies() == null ? List.of() : request.getDeficiencies();
        List<String> conditionInputs = request.getMedicalConditions() == null ? List.of() : request.getMedicalConditions();

        List<String> normalizedDeficiencies = deficiencyInputs.stream()
            .filter(StringUtils::hasText)
            .map(this::normalizeText)
            .distinct()
            .collect(Collectors.toList());
        List<String> normalizedConditions = conditionInputs.stream()
            .filter(StringUtils::hasText)
            .map(this::normalizeText)
            .distinct()
            .collect(Collectors.toList());

        String region = StringUtils.hasText(request.getRegion()) ? request.getRegion().trim() : "Global";
        String dietaryPreference = StringUtils.hasText(request.getDietaryPreference())
            ? request.getDietaryPreference().trim()
            : "No restrictions";
        List<String> currencies = request.getCurrencies() == null || request.getCurrencies().isEmpty()
            ? List.of("INR", "USD", "EUR", "GBP")
            : request.getCurrencies()
                .stream()
                .filter(StringUtils::hasText)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());

        Set<String> searchTerms = new LinkedHashSet<>();
        List<DeficiencyProfile> activeDeficiencyProfiles = new ArrayList<>();
        List<ConditionProfile> activeConditionProfiles = new ArrayList<>();

        for (String deficiency : normalizedDeficiencies) {
            DeficiencyProfile profile = resolveDeficiencyProfile(deficiency);
            if (profile == null) {
                continue;
            }
            activeDeficiencyProfiles.add(profile);
            searchTerms.addAll(profile.keywords());
        }

        for (String condition : normalizedConditions) {
            ConditionProfile profile = resolveConditionProfile(condition);
            if (profile == null) {
                continue;
            }
            activeConditionProfiles.add(profile);
            searchTerms.addAll(profile.keywords());
        }

        Map<Long, Ingredient> candidates = new LinkedHashMap<>();
        for (String keyword : searchTerms) {
            ingredientRepository.findTop300ByNameContainingIgnoreCaseOrderByNameAsc(keyword)
                .forEach(ingredient -> candidates.put(ingredient.getId(), ingredient));
            ingredientRepository.findTop300ByAliasesContainingIgnoreCaseOrderByNameAsc(keyword)
                .forEach(ingredient -> candidates.put(ingredient.getId(), ingredient));
        }

        if (candidates.isEmpty()) {
            ingredientRepository.findTop500ByOrderByNameAsc().forEach(ingredient -> candidates.put(ingredient.getId(), ingredient));
        }

        boolean vegetarian = isVegetarian(dietaryPreference);
        boolean vegan = isVegan(dietaryPreference);
        String normalizedRegion = normalizeText(region);

        List<RankedIngredient> ranked = candidates.values().stream()
            .filter(ingredient -> passesDietaryFilter(ingredient, vegetarian, vegan))
            .map(ingredient ->
                new RankedIngredient(
                    ingredient,
                    scoreIngredient(ingredient, activeDeficiencyProfiles, activeConditionProfiles, normalizedRegion)
                )
            )
            .filter(item -> item.score() > 0.3)
            .sorted(Comparator.comparingDouble(RankedIngredient::score).reversed())
            .limit(MAX_FOOD_RECOMMENDATIONS)
            .collect(Collectors.toList());

        List<DeficiencyRecommendationItem> recommendationItems = ranked.stream()
            .map(item -> toFoodItem(item.ingredient(), activeDeficiencyProfiles, activeConditionProfiles, region, currencies))
            .collect(Collectors.toCollection(ArrayList::new));

        if (Boolean.TRUE.equals(request.getIncludeSupplements())) {
            Set<String> supplementNames = new LinkedHashSet<>();
            activeDeficiencyProfiles.forEach(profile -> {
                DeficiencyRecommendationItem supplement = toDeficiencySupplementItem(profile, region, currencies);
                if (supplementNames.add(supplement.getName())) {
                    recommendationItems.add(supplement);
                }
            });
            activeConditionProfiles.forEach(profile -> {
                DeficiencyRecommendationItem supplement = toConditionSupplementItem(profile, region, currencies);
                if (supplementNames.add(supplement.getName())) {
                    recommendationItems.add(supplement);
                }
            });
        }

        DeficiencyToolsResponse response = new DeficiencyToolsResponse();
        response.setRegion(region);
        response.setDietaryPreference(dietaryPreference);
        response.setDeficiencies(
            activeDeficiencyProfiles.stream().map(DeficiencyProfile::displayName).collect(Collectors.toList())
        );
        response.setMedicalConditions(
            activeConditionProfiles.stream().map(ConditionProfile::displayName).collect(Collectors.toList())
        );
        response.setCurrencies(currencies);
        response.setGeneratedAt(Instant.now());
        response.setRecommendations(recommendationItems);
        response.setNotes(List.of(
            "Suggestions combine imported global food datasets, nutrition estimates, and deficiency/illness focused heuristics.",
            "Pricing is an averaged global estimate and can vary by city, season, and brand.",
            "This tool is educational and not a medical diagnosis. Consult a licensed doctor for treatment."
        ));
        return response;
    }

    public Map<String, Double> supportedCurrencies() {
        return foodMetadataService.getCurrencyRates();
    }

    private DeficiencyRecommendationItem toFoodItem(
        Ingredient ingredient,
        List<DeficiencyProfile> activeDeficiencyProfiles,
        List<ConditionProfile> activeConditionProfiles,
        String region,
        List<String> currencies
    ) {
        foodMetadataService.applyDefaults(ingredient);

        DeficiencyRecommendationItem item = new DeficiencyRecommendationItem();
        item.setName(ingredient.getName());
        item.setType(resolveType(ingredient.getCategory()));
        item.setCuisine(ingredient.getCuisine());
        item.setServingNote(ingredient.getServingNote());
        item.setCaloriesPer100g(ingredient.getCaloriesPer100g());
        item.setProteinPer100g(valueOrZero(ingredient.getProteinPer100g()));
        item.setCarbsPer100g(valueOrZero(ingredient.getCarbsPer100g()));
        item.setFatsPer100g(valueOrZero(ingredient.getFatsPer100g()));
        item.setFiberPer100g(valueOrZero(ingredient.getFiberPer100g()));
        item.setAveragePriceUsd(valueOrZero(ingredient.getAveragePriceUsd()));
        item.setAveragePriceUnit(ingredient.getAveragePriceUnit());
        item.setPriceByCurrency(foodMetadataService.convertUsdToCurrencies(ingredient.getAveragePriceUsd(), currencies));
        item.setRegionalAvailability(foodMetadataService.fromCsv(ingredient.getRegionalAvailability()));
        item.setSource(ingredient.getSource());
        item.setReason(buildReason(ingredient, activeDeficiencyProfiles, activeConditionProfiles, region));
        return item;
    }

    private DeficiencyRecommendationItem toDeficiencySupplementItem(
        DeficiencyProfile profile,
        String region,
        List<String> currencies
    ) {
        DeficiencyRecommendationItem item = new DeficiencyRecommendationItem();
        item.setName(String.join(" / ", profile.supplements()));
        item.setType("SUPPLEMENT");
        item.setReason(profile.tip());
        item.setCuisine("N/A");
        item.setServingNote("Daily serving as directed");
        item.setCaloriesPer100g(0.0);
        item.setProteinPer100g(0.0);
        item.setCarbsPer100g(0.0);
        item.setFatsPer100g(0.0);
        item.setFiberPer100g(0.0);
        item.setAveragePriceUsd(profile.supplementPriceUsd());
        item.setAveragePriceUnit("month");
        item.setPriceByCurrency(foodMetadataService.convertUsdToCurrencies(profile.supplementPriceUsd(), currencies));
        item.setRegionalAvailability(List.of("Global", region));
        item.setSource("CURATED_SUPPLEMENT_GUIDE");
        return item;
    }

    private DeficiencyRecommendationItem toConditionSupplementItem(
        ConditionProfile profile,
        String region,
        List<String> currencies
    ) {
        DeficiencyRecommendationItem item = new DeficiencyRecommendationItem();
        item.setName(String.join(" / ", profile.supplements()));
        item.setType("SUPPLEMENT");
        item.setReason(profile.tip());
        item.setCuisine("N/A");
        item.setServingNote("Daily serving as directed");
        item.setCaloriesPer100g(0.0);
        item.setProteinPer100g(0.0);
        item.setCarbsPer100g(0.0);
        item.setFatsPer100g(0.0);
        item.setFiberPer100g(0.0);
        item.setAveragePriceUsd(profile.supplementPriceUsd());
        item.setAveragePriceUnit("month");
        item.setPriceByCurrency(foodMetadataService.convertUsdToCurrencies(profile.supplementPriceUsd(), currencies));
        item.setRegionalAvailability(List.of("Global", region));
        item.setSource("CURATED_CONDITION_SUPPORT_GUIDE");
        return item;
    }

    private String buildReason(
        Ingredient ingredient,
        List<DeficiencyProfile> activeDeficiencyProfiles,
        List<ConditionProfile> activeConditionProfiles,
        String region
    ) {
        String normalizedName = normalizeText(ingredient.getName());
        Set<String> reasons = new LinkedHashSet<>();

        for (DeficiencyProfile profile : activeDeficiencyProfiles) {
            boolean matched = profile.keywords().stream()
                .map(this::normalizeText)
                .anyMatch(normalizedName::contains);
            if (matched) {
                reasons.add(profile.displayName());
            }
        }

        for (ConditionProfile profile : activeConditionProfiles) {
            boolean matched = profile.keywords().stream()
                .map(this::normalizeText)
                .anyMatch(normalizedName::contains);
            if (matched) {
                reasons.add(profile.displayName());
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("General nutrient support");
        }

        List<String> availability = foodMetadataService.fromCsv(ingredient.getRegionalAvailability());
        boolean regionMatch = availability.stream()
            .map(this::normalizeText)
            .anyMatch(value -> value.equals("global") || value.contains(normalizeText(region)));

        return String.format(
            "Supports %s. %s",
            String.join(", ", reasons),
            regionMatch ? "Available in your selected region." : "Regional availability may vary."
        );
    }

    private double scoreIngredient(
        Ingredient ingredient,
        List<DeficiencyProfile> activeDeficiencyProfiles,
        List<ConditionProfile> activeConditionProfiles,
        String region
    ) {
        foodMetadataService.applyDefaults(ingredient);

        String normalizedName = normalizeText(ingredient.getName());
        double score = 0.0;

        for (DeficiencyProfile profile : activeDeficiencyProfiles) {
            for (String keyword : profile.keywords()) {
                if (normalizedName.contains(normalizeText(keyword))) {
                    score += 5.0;
                }
            }
            score += deficiencyNutrientBoost(profile.key(), ingredient);
        }

        for (ConditionProfile profile : activeConditionProfiles) {
            for (String keyword : profile.keywords()) {
                if (normalizedName.contains(normalizeText(keyword))) {
                    score += 4.5;
                }
            }
            score += conditionNutrientBoost(profile.key(), ingredient);
        }

        List<String> availability = foodMetadataService.fromCsv(ingredient.getRegionalAvailability());
        boolean regionMatch = availability.stream()
            .map(this::normalizeText)
            .anyMatch(value -> value.equals("global") || value.contains(region));

        if (regionMatch) {
            score += 1.5;
        }

        return score;
    }

    private double deficiencyNutrientBoost(String deficiencyKey, Ingredient ingredient) {
        double protein = valueOrZero(ingredient.getProteinPer100g());
        double carbs = valueOrZero(ingredient.getCarbsPer100g());
        double fats = valueOrZero(ingredient.getFatsPer100g());
        double fiber = valueOrZero(ingredient.getFiberPer100g());

        return switch (deficiencyKey) {
            case "protein deficiency" -> protein * 0.24;
            case "fiber deficiency" -> fiber * 0.5;
            case "iron deficiency" -> protein * 0.10 + fiber * 0.18;
            case "vitamin b12 deficiency" -> protein * 0.2;
            case "vitamin d deficiency" -> fats * 0.16;
            case "calcium deficiency" -> protein * 0.12 + fats * 0.06;
            case "magnesium deficiency" -> fiber * 0.2 + carbs * 0.06;
            case "zinc deficiency" -> protein * 0.14;
            case "omega 3 deficiency" -> fats * 0.2;
            case "vitamin c deficiency" -> carbs * 0.08 + fiber * 0.08;
            default -> protein * 0.08 + fiber * 0.08;
        };
    }

    private double conditionNutrientBoost(String conditionKey, Ingredient ingredient) {
        double protein = valueOrZero(ingredient.getProteinPer100g());
        double carbs = valueOrZero(ingredient.getCarbsPer100g());
        double fats = valueOrZero(ingredient.getFatsPer100g());
        double fiber = valueOrZero(ingredient.getFiberPer100g());

        return switch (conditionKey) {
            case "diabetes support" -> Math.max(0.0, fiber * 0.42 + protein * 0.12 - carbs * 0.04);
            case "hypertension support" -> fiber * 0.2 + protein * 0.08 + fats * 0.03;
            case "high cholesterol support" -> fiber * 0.36 + protein * 0.1 + fats * 0.02;
            case "pcos support" -> fiber * 0.24 + protein * 0.18 + fats * 0.04;
            case "thyroid support" -> protein * 0.14 + fiber * 0.11;
            case "anemia support" -> protein * 0.12 + fiber * 0.14;
            case "fatty liver support" -> Math.max(0.0, fiber * 0.3 + protein * 0.1 - fats * 0.03);
            case "gut inflammation support" -> fiber * 0.27 + protein * 0.1;
            default -> protein * 0.08 + fiber * 0.08;
        };
    }

    private boolean passesDietaryFilter(Ingredient ingredient, boolean vegetarian, boolean vegan) {
        if (vegan) {
            return ingredient.getCategory() != IngredientCategory.MEAT
                && ingredient.getCategory() != IngredientCategory.SEAFOOD
                && ingredient.getCategory() != IngredientCategory.DAIRY;
        }
        if (vegetarian) {
            return ingredient.getCategory() != IngredientCategory.MEAT
                && ingredient.getCategory() != IngredientCategory.SEAFOOD;
        }
        return true;
    }

    private boolean isVegetarian(String dietaryPreference) {
        String normalized = normalizeText(dietaryPreference);
        return normalized.contains("veg") && !normalized.contains("non veg");
    }

    private boolean isVegan(String dietaryPreference) {
        return normalizeText(dietaryPreference).contains("vegan");
    }

    private DeficiencyProfile resolveDeficiencyProfile(String deficiency) {
        if (deficiencyProfiles.containsKey(deficiency)) {
            return deficiencyProfiles.get(deficiency);
        }

        if (deficiency.contains("iron")) {
            return deficiencyProfiles.get("iron deficiency");
        }
        if (deficiency.contains("protein")) {
            return deficiencyProfiles.get("protein deficiency");
        }
        if (deficiency.contains("fiber") || deficiency.contains("fibre")) {
            return deficiencyProfiles.get("fiber deficiency");
        }
        if (deficiency.contains("vitamin d")) {
            return deficiencyProfiles.get("vitamin d deficiency");
        }
        if (deficiency.contains("vitamin b12")) {
            return deficiencyProfiles.get("vitamin b12 deficiency");
        }
        if (deficiency.contains("vitamin c")) {
            return deficiencyProfiles.get("vitamin c deficiency");
        }
        if (deficiency.contains("calcium")) {
            return deficiencyProfiles.get("calcium deficiency");
        }
        if (deficiency.contains("magnesium")) {
            return deficiencyProfiles.get("magnesium deficiency");
        }
        if (deficiency.contains("zinc")) {
            return deficiencyProfiles.get("zinc deficiency");
        }
        if (deficiency.contains("omega")) {
            return deficiencyProfiles.get("omega 3 deficiency");
        }
        return null;
    }

    private ConditionProfile resolveConditionProfile(String condition) {
        if (conditionProfiles.containsKey(condition)) {
            return conditionProfiles.get(condition);
        }

        if (condition.contains("diabet") || condition.contains("sugar")) {
            return conditionProfiles.get("diabetes support");
        }
        if (condition.contains("hypertension") || condition.contains("high bp") || condition.equals("bp")) {
            return conditionProfiles.get("hypertension support");
        }
        if (condition.contains("cholesterol") || condition.contains("lipid")) {
            return conditionProfiles.get("high cholesterol support");
        }
        if (condition.contains("pcos") || condition.contains("pcod")) {
            return conditionProfiles.get("pcos support");
        }
        if (condition.contains("thyroid")) {
            return conditionProfiles.get("thyroid support");
        }
        if (condition.contains("anemia") || condition.contains("anaemia")) {
            return conditionProfiles.get("anemia support");
        }
        if (condition.contains("fatty liver") || condition.contains("liver")) {
            return conditionProfiles.get("fatty liver support");
        }
        if (condition.contains("ibs") || condition.contains("gut") || condition.contains("digestion")) {
            return conditionProfiles.get("gut inflammation support");
        }
        return null;
    }

    private String resolveType(IngredientCategory category) {
        return switch (category) {
            case FRUIT -> "FRUIT";
            case VEGETABLE -> "VEGETABLE";
            case MEAT, SEAFOOD, LEGUME, RICE, GRAIN, DAIRY, JUICE, SNACK, OIL, SAUCE, SPICE, BEVERAGE, OTHER ->
                "FOOD";
        };
    }

    private String normalizeText(String value) {
        return foodMetadataService.normalizeToken(value);
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private void seedDeficiencyProfiles() {
        registerDeficiencyProfile(
            "iron deficiency",
            "Iron Deficiency",
            List.of("spinach", "lentil", "chickpea", "beef", "liver", "tofu", "mutton"),
            List.of("Iron Bisglycinate", "Ferrous Sulfate", "Vitamin C Co-Supplement"),
            16.0,
            "Prioritize iron-rich foods with vitamin C pairing for better absorption."
        );
        registerDeficiencyProfile(
            "vitamin d deficiency",
            "Vitamin D Deficiency",
            List.of("salmon", "mackerel", "egg", "mushroom", "milk", "sardine"),
            List.of("Vitamin D3", "Vitamin K2", "Omega 3"),
            14.0,
            "Focus on vitamin D foods plus safe sunlight exposure."
        );
        registerDeficiencyProfile(
            "vitamin b12 deficiency",
            "Vitamin B12 Deficiency",
            List.of("egg", "fish", "chicken", "beef", "yogurt", "milk"),
            List.of("Methylcobalamin B12", "B-Complex", "Folate"),
            18.0,
            "B12-rich animal or fortified foods help improve neurological and energy health."
        );
        registerDeficiencyProfile(
            "protein deficiency",
            "Protein Deficiency",
            List.of("chicken", "egg", "paneer", "tofu", "lentil", "beans", "fish"),
            List.of("Whey Protein", "Plant Protein Blend", "Essential Amino Acids"),
            24.0,
            "Build each meal around quality protein sources."
        );
        registerDeficiencyProfile(
            "fiber deficiency",
            "Fiber Deficiency",
            List.of("oats", "apple", "pear", "broccoli", "lentil", "beans", "brown rice"),
            List.of("Psyllium Husk", "Prebiotic Fiber", "Inulin"),
            10.0,
            "Increase fiber gradually with hydration for gut comfort."
        );
        registerDeficiencyProfile(
            "vitamin c deficiency",
            "Vitamin C Deficiency",
            List.of("orange", "guava", "lemon", "berry", "broccoli", "bell pepper"),
            List.of("Vitamin C", "Citrus Bioflavonoids"),
            9.0,
            "Fresh fruits and peppers are efficient vitamin C choices."
        );
        registerDeficiencyProfile(
            "calcium deficiency",
            "Calcium Deficiency",
            List.of("milk", "curd", "paneer", "cheese", "tofu", "sesame"),
            List.of("Calcium Citrate", "Calcium + D3", "Magnesium + Calcium"),
            12.0,
            "Pair calcium foods with vitamin D support."
        );
        registerDeficiencyProfile(
            "magnesium deficiency",
            "Magnesium Deficiency",
            List.of("almond", "cashew", "spinach", "banana", "beans", "pumpkin"),
            List.of("Magnesium Glycinate", "Magnesium Citrate", "Electrolyte Blend"),
            13.0,
            "Nuts, greens, and legumes are practical magnesium boosters."
        );
        registerDeficiencyProfile(
            "zinc deficiency",
            "Zinc Deficiency",
            List.of("beef", "chickpea", "pumpkin seed", "cashew", "egg", "seafood"),
            List.of("Zinc Picolinate", "Zinc + Copper"),
            11.0,
            "Combine zinc foods with adequate protein intake."
        );
        registerDeficiencyProfile(
            "omega 3 deficiency",
            "Omega-3 Deficiency",
            List.of("salmon", "sardine", "mackerel", "flaxseed", "walnut"),
            List.of("Fish Oil", "Algal Omega 3", "Flaxseed Oil Capsules"),
            15.0,
            "Fatty fish or algae-based omega supplements are effective."
        );
    }

    private void seedConditionProfiles() {
        registerConditionProfile(
            "diabetes support",
            "Diabetes Support",
            List.of("oats", "lentil", "beans", "broccoli", "apple", "pear", "brown rice"),
            List.of("Chromium", "Berberine", "Alpha Lipoic Acid"),
            18.0,
            "Focus on high-fiber, lower glycemic foods and steady meal timing."
        );
        registerConditionProfile(
            "hypertension support",
            "Hypertension Support",
            List.of("banana", "spinach", "beetroot", "salmon", "oats", "yogurt"),
            List.of("Omega 3", "Magnesium", "CoQ10"),
            16.0,
            "Prioritize potassium-rich foods and limit high sodium processed items."
        );
        registerConditionProfile(
            "high cholesterol support",
            "High Cholesterol Support",
            List.of("oats", "walnut", "almond", "avocado", "beans", "salmon"),
            List.of("Plant Sterols", "Omega 3", "Soluble Fiber"),
            19.0,
            "Increase soluble fiber and unsaturated fats while reducing trans-fat snacks."
        );
        registerConditionProfile(
            "pcos support",
            "PCOS Support",
            List.of("egg", "chickpea", "lentil", "spinach", "salmon", "berry"),
            List.of("Inositol", "Omega 3", "Vitamin D3"),
            22.0,
            "Balanced protein + fiber meals can support insulin and hormone balance."
        );
        registerConditionProfile(
            "thyroid support",
            "Thyroid Support",
            List.of("egg", "fish", "seafood", "milk", "yogurt", "nuts"),
            List.of("Selenium", "Iodine", "Zinc"),
            17.0,
            "Include iodine, selenium, and protein rich foods consistently."
        );
        registerConditionProfile(
            "anemia support",
            "Anemia Support",
            List.of("beef", "liver", "lentil", "spinach", "chickpea", "egg"),
            List.of("Iron", "B12", "Folate"),
            15.0,
            "Pair iron rich foods with vitamin C sources for better absorption."
        );
        registerConditionProfile(
            "fatty liver support",
            "Fatty Liver Support",
            List.of("oats", "broccoli", "fish", "beans", "apple", "leafy greens"),
            List.of("Omega 3", "Vitamin E", "Milk Thistle"),
            21.0,
            "Favor high-fiber whole foods and reduce sugary or ultra-processed items."
        );
        registerConditionProfile(
            "gut inflammation support",
            "Gut Inflammation Support",
            List.of("yogurt", "curd", "banana", "oats", "rice", "broth"),
            List.of("Probiotics", "L-Glutamine", "Prebiotic Fiber"),
            20.0,
            "Gentle, fiber-aware meals with probiotics can support digestive recovery."
        );
    }

    private void registerDeficiencyProfile(
        String key,
        String displayName,
        List<String> keywords,
        List<String> supplements,
        double supplementPriceUsd,
        String tip
    ) {
        deficiencyProfiles.put(key, new DeficiencyProfile(key, displayName, keywords, supplements, supplementPriceUsd, tip));
    }

    private void registerConditionProfile(
        String key,
        String displayName,
        List<String> keywords,
        List<String> supplements,
        double supplementPriceUsd,
        String tip
    ) {
        conditionProfiles.put(key, new ConditionProfile(key, displayName, keywords, supplements, supplementPriceUsd, tip));
    }

    private record RankedIngredient(Ingredient ingredient, double score) {
    }

    private record DeficiencyProfile(
        String key,
        String displayName,
        List<String> keywords,
        List<String> supplements,
        double supplementPriceUsd,
        String tip
    ) {
    }

    private record ConditionProfile(
        String key,
        String displayName,
        List<String> keywords,
        List<String> supplements,
        double supplementPriceUsd,
        String tip
    ) {
    }
}

package com.calorietracker.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.DietPlanSection;
import com.calorietracker.dto.DeficiencyInsight;
import com.calorietracker.dto.DeficiencyRecommendationItem;
import com.calorietracker.dto.DeficiencyToolsRequest;
import com.calorietracker.dto.DeficiencyToolsResponse;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.IngredientRepository;

@Service
public class ToolsRecommendationService {

    private static final int MAX_FOOD_RECOMMENDATIONS = 26;
    private static final Set<String> MINERAL_DEFICIENCY_KEYS = Set.of(
        "iron deficiency",
        "calcium deficiency",
        "magnesium deficiency",
        "zinc deficiency",
        "potassium deficiency",
        "phosphorus deficiency",
        "iodine deficiency",
        "selenium deficiency",
        "copper deficiency",
        "manganese deficiency",
        "chromium deficiency",
        "molybdenum deficiency",
        "sodium deficiency",
        "chloride deficiency",
        "fluoride deficiency"
    );
    private static final Set<String> FRUIT_TOKENS = Set.of(
        "apple",
        "banana",
        "orange",
        "guava",
        "berry",
        "pear",
        "lemon",
        "pineapple",
        "mango",
        "papaya",
        "avocado"
    );
    private static final Set<String> VEGETABLE_TOKENS = Set.of(
        "spinach",
        "kale",
        "broccoli",
        "carrot",
        "beetroot",
        "cabbage",
        "lettuce",
        "mushroom",
        "sweet potato",
        "potato",
        "bell pepper",
        "tomato"
    );
    private static final Set<String> NUTS_SEEDS_TOKENS = Set.of(
        "almond",
        "cashew",
        "walnut",
        "peanut",
        "sunflower",
        "pumpkin",
        "sesame",
        "flaxseed",
        "chia",
        "pistachio"
    );
    private static final Set<String> CEREAL_GRAIN_TOKENS = Set.of(
        "oats",
        "rice",
        "barley",
        "wheat",
        "quinoa",
        "millet",
        "corn",
        "brown rice",
        "whole wheat"
    );
    private static final Set<String> LEGUME_TOKENS = Set.of(
        "lentil",
        "beans",
        "chickpea",
        "peas",
        "tofu"
    );
    private static final Set<String> DAIRY_TOKENS = Set.of(
        "milk",
        "yogurt",
        "curd",
        "paneer",
        "cheese"
    );
    private static final Set<String> ANIMAL_PROTEIN_TOKENS = Set.of(
        "egg",
        "chicken",
        "fish",
        "salmon",
        "tuna",
        "beef",
        "mutton",
        "pork",
        "liver",
        "sardine",
        "mackerel",
        "shrimp",
        "seafood"
    );
    private static final Set<String> HYDRATION_TOKENS = Set.of(
        "water",
        "coconut water",
        "broth",
        "soup",
        "juice"
    );

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final Map<String, DeficiencyProfile> deficiencyProfiles = new LinkedHashMap<>();
    private final Map<String, List<String>> deficiencySymptoms = new LinkedHashMap<>();
    private final Map<String, List<String>> deficiencySources = new LinkedHashMap<>();
    private final Map<String, ConditionProfile> conditionProfiles = new LinkedHashMap<>();

    public ToolsRecommendationService(IngredientRepository ingredientRepository, FoodMetadataService foodMetadataService) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
        seedDeficiencyProfiles();
        seedDeficiencyInsights();
        seedConditionProfiles();
    }

    public DeficiencyToolsResponse buildRecommendations(DeficiencyToolsRequest request) {
        List<String> deficiencyInputs = request.getDeficiencies() == null ? List.of() : request.getDeficiencies();
        List<String> conditionInputs = request.getMedicalConditions() == null ? List.of() : request.getMedicalConditions();
        List<String> likedFoodInputs = request.getLikedFoods() == null ? List.of() : request.getLikedFoods();

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
        List<String> likedFoods = likedFoodInputs.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());
        List<String> normalizedLikedFoods = likedFoods.stream()
            .map(this::normalizeText)
            .filter(StringUtils::hasText)
            .distinct()
            .collect(Collectors.toList());
        int dailyCalorieGoal = sanitizeDailyCalorieGoal(request.getDailyCalorieGoal());

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
        Set<String> deficiencyKeys = new LinkedHashSet<>();
        Set<String> conditionKeys = new LinkedHashSet<>();

        for (String deficiency : normalizedDeficiencies) {
            DeficiencyProfile profile = resolveDeficiencyProfile(deficiency);
            if (profile == null) {
                continue;
            }
            if (!deficiencyKeys.add(profile.key())) {
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
            if (!conditionKeys.add(profile.key())) {
                continue;
            }
            activeConditionProfiles.add(profile);
            searchTerms.addAll(profile.keywords());
        }
        searchTerms.addAll(likedFoods);

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
                    scoreIngredient(
                        ingredient,
                        activeDeficiencyProfiles,
                        activeConditionProfiles,
                        normalizedRegion,
                        normalizedLikedFoods,
                        dailyCalorieGoal
                    )
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
        response.setDietPlan(
            buildDietPlan(
                recommendationItems,
                activeDeficiencyProfiles,
                activeConditionProfiles,
                dietaryPreference,
                region,
                dailyCalorieGoal,
                likedFoods
            )
        );
        response.setDeficiencyInsights(buildDeficiencyInsights(activeDeficiencyProfiles));
        List<String> notes = new ArrayList<>(List.of(
            "Suggestions combine imported global food datasets, nutrition estimates, and deficiency/illness focused heuristics.",
            "Pricing is an averaged global estimate and can vary by city, season, and brand.",
            "Deficiency symptom references are curated from trusted public health sources (NIH ODS, MedlinePlus, NHS).",
            "This tool is educational and not a medical diagnosis. Consult a licensed doctor for treatment."
        ));
        notes.add("Personalized calorie target applied: " + dailyCalorieGoal + " kcal/day.");
        if (!likedFoods.isEmpty()) {
            notes.add("Liked foods prioritized: " + String.join(", ", likedFoods.stream().limit(8).collect(Collectors.toList())));
        }
        response.setNotes(notes);
        return response;
    }

    public Map<String, Double> supportedCurrencies() {
        return foodMetadataService.getCurrencyRates();
    }

    public Map<String, List<String>> supportedDeficiencies() {
        List<String> vitamins = deficiencyProfiles.values().stream()
            .filter(profile -> profile.key().startsWith("vitamin "))
            .map(DeficiencyProfile::displayName)
            .sorted()
            .collect(Collectors.toList());

        List<String> minerals = deficiencyProfiles.values().stream()
            .filter(profile -> MINERAL_DEFICIENCY_KEYS.contains(profile.key()))
            .map(DeficiencyProfile::displayName)
            .sorted()
            .collect(Collectors.toList());

        Set<String> vitaminSet = vitamins.stream().map(this::normalizeText).collect(Collectors.toCollection(HashSet::new));
        Set<String> mineralSet = minerals.stream().map(this::normalizeText).collect(Collectors.toCollection(HashSet::new));

        List<String> other = deficiencyProfiles.values().stream()
            .map(DeficiencyProfile::displayName)
            .filter(name -> !vitaminSet.contains(normalizeText(name)) && !mineralSet.contains(normalizeText(name)))
            .sorted()
            .collect(Collectors.toList());

        Map<String, List<String>> response = new LinkedHashMap<>();
        response.put("vitamins", vitamins);
        response.put("minerals", minerals);
        response.put("other", other);
        return response;
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

    private List<DietPlanSection> buildDietPlan(
        List<DeficiencyRecommendationItem> recommendations,
        List<DeficiencyProfile> deficiencyProfilesActive,
        List<ConditionProfile> conditionProfilesActive,
        String dietaryPreference,
        String region,
        int dailyCalorieGoal,
        List<String> likedFoods
    ) {
        List<DeficiencyRecommendationItem> foods = recommendations == null
            ? List.of()
            : recommendations.stream()
                .filter(item -> !"SUPPLEMENT".equalsIgnoreCase(item.getType()))
                .collect(Collectors.toList());

        List<DeficiencyRecommendationItem> prioritizedFoods = prioritizeFoodsByPreference(foods, likedFoods);
        List<String> breakfast = pickFoodNames(prioritizedFoods, 0, 3);
        List<String> lunch = pickFoodNames(prioritizedFoods, 3, 4);
        List<String> evening = pickFoodNames(prioritizedFoods, 7, 3);
        List<String> dinner = pickFoodNames(prioritizedFoods, 10, 4);

        int breakfastTarget = Math.round(dailyCalorieGoal * 0.25f);
        int lunchTarget = Math.round(dailyCalorieGoal * 0.35f);
        int eveningTarget = Math.round(dailyCalorieGoal * 0.15f);
        int dinnerTarget = Math.max(120, dailyCalorieGoal - (breakfastTarget + lunchTarget + eveningTarget));

        if (breakfast.isEmpty()) {
            breakfast = List.of("Oats + fruit + protein source");
        }
        if (lunch.isEmpty()) {
            lunch = List.of("Balanced plate: grains + protein + vegetables");
        }
        if (evening.isEmpty()) {
            evening = List.of("Fruit + nuts or yogurt");
        }
        if (dinner.isEmpty()) {
            dinner = List.of("Lean protein + high-fiber vegetables");
        }

        List<String> focus = new ArrayList<>();
        if (deficiencyProfilesActive != null) {
            deficiencyProfilesActive.stream()
                .map(DeficiencyProfile::tip)
                .filter(StringUtils::hasText)
                .limit(4)
                .forEach(focus::add);
        }
        if (conditionProfilesActive != null) {
            conditionProfilesActive.stream()
                .map(ConditionProfile::tip)
                .filter(StringUtils::hasText)
                .limit(2)
                .forEach(focus::add);
        }

        if (focus.isEmpty()) {
            focus = List.of("Keep meals consistent and include whole foods from multiple food groups.");
        }

        List<DietPlanSection> plan = new ArrayList<>();
        plan.add(
            new DietPlanSection(
                "Morning",
                List.of(
                    "Hydration: 300-500 ml water after waking.",
                    "Target calories: " + breakfastTarget + " kcal",
                    "Breakfast: " + String.join(", ", breakfast)
                )
            )
        );
        plan.add(
            new DietPlanSection(
                "Lunch",
                List.of(
                    "Target calories: " + lunchTarget + " kcal",
                    "Main plate: " + String.join(", ", lunch)
                )
            )
        );
        plan.add(
            new DietPlanSection(
                "Evening",
                List.of(
                    "Target calories: " + eveningTarget + " kcal",
                    "Snack: " + String.join(", ", evening)
                )
            )
        );
        plan.add(
            new DietPlanSection(
                "Dinner",
                List.of(
                    "Target calories: " + dinnerTarget + " kcal",
                    "Dinner plate: " + String.join(", ", dinner),
                    "Keep dinner lighter than lunch for easier digestion."
                )
            )
        );
        plan.add(
            new DietPlanSection(
                "Deficiency Focus",
                focus
            )
        );
        plan.add(
            new DietPlanSection(
                "Regional Note",
                List.of(
                    "Region selected: " + (StringUtils.hasText(region) ? region : "Global"),
                    "Diet preference: " + (StringUtils.hasText(dietaryPreference) ? dietaryPreference : "No restrictions")
                )
            )
        );
        List<String> personalization = new ArrayList<>();
        personalization.add("Daily goal: " + dailyCalorieGoal + " kcal/day");
        if (likedFoods != null && !likedFoods.isEmpty()) {
            personalization.add("Preferred foods: " + String.join(", ", likedFoods.stream().limit(8).collect(Collectors.toList())));
        } else {
            personalization.add("Preferred foods: not provided");
        }
        plan.add(new DietPlanSection("Personalization", personalization));
        return plan;
    }

    private List<DeficiencyInsight> buildDeficiencyInsights(List<DeficiencyProfile> activeDeficiencyProfiles) {
        if (activeDeficiencyProfiles == null || activeDeficiencyProfiles.isEmpty()) {
            return List.of();
        }

        return activeDeficiencyProfiles.stream()
            .map(profile -> {
                DeficiencyInsight insight = new DeficiencyInsight();
                insight.setName(profile.displayName());
                insight.setCategory(resolveDeficiencyCategory(profile.key()));
                insight.setTip(profile.tip());
                insight.setSymptoms(
                    deficiencySymptoms.getOrDefault(
                        profile.key(),
                        List.of("Fatigue", "Reduced performance", "Seek medical evaluation for confirmation")
                    )
                );
                insight.setRecommendedFoods(profile.keywords());
                insight.setNaturalFoodPlan(buildNaturalFoodPlan(profile));
                insight.setRecommendedSupplements(profile.supplements());
                insight.setSources(
                    deficiencySources.getOrDefault(
                        profile.key(),
                        List.of(
                            "https://ods.od.nih.gov/factsheets/list-all/",
                            "https://medlineplus.gov/nutrition.html"
                        )
                    )
                );
                return insight;
            })
            .collect(Collectors.toList());
    }

    private List<String> buildNaturalFoodPlan(DeficiencyProfile profile) {
        if (profile == null || profile.keywords() == null || profile.keywords().isEmpty()) {
            return List.of(
                "Guava (Fruit) - 150 g/day",
                "Spinach (Vegetable) - 100 g/day",
                "Almonds (Nuts/Seeds) - 30 g/day",
                "Oats (Cereal/Grain) - 80 g/day"
            );
        }

        LinkedHashSet<String> uniqueFoods = profile.keywords().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> plan = new ArrayList<>();
        boolean hasFruit = false;
        boolean hasVegetable = false;
        boolean hasNuts = false;
        boolean hasCereal = false;

        for (String food : uniqueFoods) {
            String normalizedFood = normalizeText(food);
            String naturalCategory = naturalFoodCategory(normalizedFood);
            String unit = suggestedPortionUnit(normalizedFood);
            int amount = suggestedPortionAmount(naturalCategory, unit);
            plan.add(formatNaturalFoodPortion(food, naturalCategory, amount, unit));

            hasFruit = hasFruit || "Fruit".equals(naturalCategory);
            hasVegetable = hasVegetable || "Vegetable".equals(naturalCategory);
            hasNuts = hasNuts || "Nuts/Seeds".equals(naturalCategory);
            hasCereal = hasCereal || "Cereal/Grain".equals(naturalCategory);

            if (plan.size() >= 8) {
                break;
            }
        }

        if (!hasFruit && plan.size() < 8) {
            plan.add("Guava (Fruit) - 150 g/day");
        }
        if (!hasVegetable && plan.size() < 8) {
            plan.add("Spinach (Vegetable) - 100 g/day");
        }
        if (!hasNuts && plan.size() < 8) {
            plan.add("Almonds (Nuts/Seeds) - 30 g/day");
        }
        if (!hasCereal && plan.size() < 8) {
            plan.add("Oats (Cereal/Grain) - 80 g/day");
        }

        return plan.stream().limit(8).collect(Collectors.toList());
    }

    private String naturalFoodCategory(String normalizedFood) {
        if (containsAnyToken(normalizedFood, FRUIT_TOKENS)) {
            return "Fruit";
        }
        if (containsAnyToken(normalizedFood, VEGETABLE_TOKENS)) {
            return "Vegetable";
        }
        if (containsAnyToken(normalizedFood, NUTS_SEEDS_TOKENS)) {
            return "Nuts/Seeds";
        }
        if (containsAnyToken(normalizedFood, CEREAL_GRAIN_TOKENS)) {
            return "Cereal/Grain";
        }
        if (containsAnyToken(normalizedFood, LEGUME_TOKENS)) {
            return "Legume";
        }
        if (containsAnyToken(normalizedFood, DAIRY_TOKENS)) {
            return "Dairy";
        }
        if (containsAnyToken(normalizedFood, ANIMAL_PROTEIN_TOKENS)) {
            return "Animal Protein";
        }
        if (containsAnyToken(normalizedFood, HYDRATION_TOKENS)) {
            return "Hydration";
        }
        return "Whole Food";
    }

    private boolean containsAnyToken(String normalizedValue, Set<String> tokens) {
        if (!StringUtils.hasText(normalizedValue) || tokens == null || tokens.isEmpty()) {
            return false;
        }

        for (String token : tokens) {
            if (normalizedValue.contains(normalizeText(token))) {
                return true;
            }
        }
        return false;
    }

    private String suggestedPortionUnit(String normalizedFood) {
        if (!StringUtils.hasText(normalizedFood)) {
            return "g/day";
        }

        if (normalizedFood.contains("water")
            || normalizedFood.contains("broth")
            || normalizedFood.contains("soup")
            || normalizedFood.contains("juice")
            || normalizedFood.contains("milk")
            || normalizedFood.contains("curd")
            || normalizedFood.contains("yogurt")) {
            return "ml/day";
        }

        return "g/day";
    }

    private int suggestedPortionAmount(String category, String unit) {
        if ("ml/day".equalsIgnoreCase(unit)) {
            return switch (category) {
                case "Hydration" -> 250;
                case "Dairy" -> 200;
                default -> 180;
            };
        }

        return switch (category) {
            case "Fruit" -> 150;
            case "Vegetable" -> 100;
            case "Nuts/Seeds" -> 30;
            case "Cereal/Grain" -> 80;
            case "Legume" -> 90;
            case "Dairy" -> 120;
            case "Animal Protein" -> 100;
            default -> 80;
        };
    }

    private String formatNaturalFoodPortion(String food, String category, int amount, String unit) {
        return String.format("%s (%s) - %d %s", toDisplayFoodName(food), category, amount, unit);
    }

    private String toDisplayFoodName(String food) {
        if (!StringUtils.hasText(food)) {
            return "Food";
        }

        String[] words = food.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            String normalized = word.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(normalized.charAt(0)));
            if (normalized.length() > 1) {
                builder.append(normalized.substring(1));
            }
        }
        return builder.toString();
    }

    private String resolveDeficiencyCategory(String key) {
        if (!StringUtils.hasText(key)) {
            return "Other";
        }
        if (key.startsWith("vitamin ")) {
            return "Vitamin";
        }
        if (MINERAL_DEFICIENCY_KEYS.contains(key)) {
            return "Mineral";
        }
        return "Other";
    }

    private List<String> pickFoodNames(List<DeficiencyRecommendationItem> foods, int start, int maxItems) {
        if (foods == null || foods.isEmpty() || maxItems <= 0) {
            return List.of();
        }

        int safeStart = Math.max(0, Math.min(start, foods.size()));
        return foods.stream()
            .skip(safeStart)
            .map(DeficiencyRecommendationItem::getName)
            .filter(StringUtils::hasText)
            .distinct()
            .limit(maxItems)
            .collect(Collectors.toList());
    }

    private List<DeficiencyRecommendationItem> prioritizeFoodsByPreference(
        List<DeficiencyRecommendationItem> foods,
        List<String> likedFoods
    ) {
        if (foods == null || foods.isEmpty()) {
            return List.of();
        }
        if (likedFoods == null || likedFoods.isEmpty()) {
            return foods;
        }

        return foods.stream()
            .sorted(
                Comparator
                    .comparingInt((DeficiencyRecommendationItem item) -> likedFoodMatchScore(item.getName(), likedFoods))
                    .reversed()
                    .thenComparing(item -> item.getCaloriesPer100g() == null ? 0.0 : item.getCaloriesPer100g())
            )
            .collect(Collectors.toList());
    }

    private int likedFoodMatchScore(String foodName, List<String> likedFoods) {
        if (!StringUtils.hasText(foodName) || likedFoods == null || likedFoods.isEmpty()) {
            return 0;
        }

        String normalizedName = normalizeText(foodName);
        int score = 0;
        for (String likedFood : likedFoods) {
            String normalizedLikedFood = normalizeText(likedFood);
            if (!StringUtils.hasText(normalizedLikedFood)) {
                continue;
            }

            if (normalizedName.equals(normalizedLikedFood)) {
                score += 5;
                continue;
            }
            if (normalizedName.contains(normalizedLikedFood) || normalizedLikedFood.contains(normalizedName)) {
                score += 3;
                continue;
            }

            for (String token : normalizedLikedFood.split(" ")) {
                if (token.length() >= 3 && normalizedName.contains(token)) {
                    score += 1;
                }
            }
        }
        return score;
    }

    private int sanitizeDailyCalorieGoal(Integer requestGoal) {
        if (requestGoal == null) {
            return 2200;
        }
        return Math.max(1000, Math.min(requestGoal, 5000));
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
        String region,
        List<String> likedFoods,
        int dailyCalorieGoal
    ) {
        foodMetadataService.applyDefaults(ingredient);

        String normalizedName = normalizeText(ingredient.getName());
        String normalizedAliases = normalizeText(ingredient.getAliases());
        double calories = valueOrZero(ingredient.getCaloriesPer100g());
        double protein = valueOrZero(ingredient.getProteinPer100g());
        double carbs = valueOrZero(ingredient.getCarbsPer100g());
        double fats = valueOrZero(ingredient.getFatsPer100g());
        double fiber = valueOrZero(ingredient.getFiberPer100g());
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

        int preferenceMatchScore = likedFoodMatchScore(ingredient.getName(), likedFoods);
        if (preferenceMatchScore > 0) {
            score += Math.min(9.0, preferenceMatchScore * 1.8);
        }
        if (StringUtils.hasText(normalizedAliases) && likedFoods != null) {
            for (String likedFood : likedFoods) {
                String normalizedLikedFood = normalizeText(likedFood);
                if (StringUtils.hasText(normalizedLikedFood) && normalizedAliases.contains(normalizedLikedFood)) {
                    score += 2.4;
                }
            }
        }

        if (dailyCalorieGoal <= 1800) {
            score += Math.max(0.0, 2.2 - (calories / 140.0));
            score += protein * 0.08 + fiber * 0.12;
            score -= Math.max(0.0, fats - 20.0) * 0.05;
        } else if (dailyCalorieGoal >= 2800) {
            score += Math.min(2.6, calories / 110.0);
            score += protein * 0.11 + carbs * 0.04;
        } else {
            score += protein * 0.08 + fiber * 0.09;
            score += Math.max(0.0, 1.8 - (Math.abs(calories - 180.0) / 110.0));
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
            case "vitamin a deficiency" -> fiber * 0.14 + fats * 0.08;
            case "vitamin b1 deficiency" -> carbs * 0.08 + fiber * 0.14;
            case "vitamin b2 deficiency" -> protein * 0.11 + fats * 0.05;
            case "vitamin b3 deficiency" -> protein * 0.14 + carbs * 0.05;
            case "vitamin b5 deficiency" -> protein * 0.10 + carbs * 0.07;
            case "vitamin b6 deficiency" -> protein * 0.12 + fiber * 0.08;
            case "vitamin b7 deficiency" -> protein * 0.09 + fats * 0.08;
            case "vitamin b9 deficiency" -> fiber * 0.18 + protein * 0.07;
            case "vitamin b12 deficiency" -> protein * 0.2;
            case "vitamin d deficiency" -> fats * 0.16;
            case "vitamin e deficiency" -> fats * 0.2 + fiber * 0.05;
            case "vitamin k deficiency" -> fiber * 0.2 + protein * 0.04;
            case "calcium deficiency" -> protein * 0.12 + fats * 0.06;
            case "magnesium deficiency" -> fiber * 0.2 + carbs * 0.06;
            case "zinc deficiency" -> protein * 0.14;
            case "potassium deficiency" -> fiber * 0.15 + carbs * 0.06;
            case "phosphorus deficiency" -> protein * 0.16 + carbs * 0.03;
            case "iodine deficiency" -> protein * 0.14 + fats * 0.05;
            case "selenium deficiency" -> protein * 0.13 + fats * 0.04;
            case "copper deficiency" -> fiber * 0.18 + protein * 0.05;
            case "manganese deficiency" -> fiber * 0.19 + carbs * 0.04;
            case "chromium deficiency" -> fiber * 0.14 + protein * 0.06;
            case "molybdenum deficiency" -> protein * 0.08 + fiber * 0.15;
            case "sodium deficiency", "chloride deficiency" -> carbs * 0.06 + protein * 0.06;
            case "fluoride deficiency" -> protein * 0.05 + fiber * 0.10;
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

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private DeficiencyProfile resolveDeficiencyProfile(String deficiency) {
        if (deficiencyProfiles.containsKey(deficiency)) {
            return deficiencyProfiles.get(deficiency);
        }

        if (containsAny(deficiency, "fiber", "fibre")) {
            return deficiencyProfiles.get("fiber deficiency");
        }
        if (containsAny(deficiency, "omega", "omega-3", "omega 3")) {
            return deficiencyProfiles.get("omega 3 deficiency");
        }
        if (containsAny(deficiency, "thiamine")) {
            return deficiencyProfiles.get("vitamin b1 deficiency");
        }
        if (containsAny(deficiency, "riboflavin")) {
            return deficiencyProfiles.get("vitamin b2 deficiency");
        }
        if (containsAny(deficiency, "niacin")) {
            return deficiencyProfiles.get("vitamin b3 deficiency");
        }
        if (containsAny(deficiency, "pantothenic")) {
            return deficiencyProfiles.get("vitamin b5 deficiency");
        }
        if (containsAny(deficiency, "biotin")) {
            return deficiencyProfiles.get("vitamin b7 deficiency");
        }
        if (containsAny(deficiency, "folate", "folic")) {
            return deficiencyProfiles.get("vitamin b9 deficiency");
        }

        for (DeficiencyProfile profile : deficiencyProfiles.values()) {
            String key = profile.key();
            if (deficiency.contains(key)) {
                return profile;
            }
            String base = key.replace(" deficiency", "");
            if (deficiency.contains(base)) {
                return profile;
            }
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
            "vitamin a deficiency",
            "Vitamin A Deficiency",
            List.of("carrot", "spinach", "sweet potato", "egg", "liver", "kale"),
            List.of("Vitamin A", "Beta-Carotene"),
            11.0,
            "Use orange vegetables and leafy greens with healthy fats to improve vitamin A uptake."
        );
        registerDeficiencyProfile(
            "vitamin b1 deficiency",
            "Vitamin B1 Deficiency",
            List.of("oats", "brown rice", "lentil", "beans", "sunflower", "pork"),
            List.of("Thiamine B1", "B-Complex"),
            10.0,
            "Whole grains, legumes, and seeds help restore thiamine levels."
        );
        registerDeficiencyProfile(
            "vitamin b2 deficiency",
            "Vitamin B2 Deficiency",
            List.of("milk", "yogurt", "egg", "almond", "mushroom", "spinach"),
            List.of("Riboflavin B2", "B-Complex"),
            10.0,
            "Include dairy or fortified alternatives plus mushrooms and almonds."
        );
        registerDeficiencyProfile(
            "vitamin b3 deficiency",
            "Vitamin B3 Deficiency",
            List.of("chicken", "tuna", "salmon", "peanut", "mushroom", "brown rice"),
            List.of("Niacin B3", "B-Complex"),
            10.0,
            "Lean meats, fish, peanuts, and whole grains support niacin intake."
        );
        registerDeficiencyProfile(
            "vitamin b5 deficiency",
            "Vitamin B5 Deficiency",
            List.of("avocado", "chicken", "mushroom", "egg", "yogurt", "sweet potato"),
            List.of("Pantothenic Acid B5", "B-Complex"),
            10.0,
            "Use a mixed plate with eggs, mushrooms, and legumes for B5 support."
        );
        registerDeficiencyProfile(
            "vitamin b6 deficiency",
            "Vitamin B6 Deficiency",
            List.of("chickpea", "banana", "potato", "chicken", "fish", "oats"),
            List.of("Pyridoxine B6", "B-Complex"),
            10.0,
            "Chickpeas, potatoes, and fish are practical B6-rich foods."
        );
        registerDeficiencyProfile(
            "vitamin b7 deficiency",
            "Vitamin B7 Deficiency",
            List.of("egg", "almond", "salmon", "sweet potato", "sunflower", "peanut"),
            List.of("Biotin B7", "Hair & Nail Biotin Complex"),
            12.0,
            "Eggs, nuts, and seeds are useful foods for biotin support."
        );
        registerDeficiencyProfile(
            "vitamin b9 deficiency",
            "Vitamin B9 Deficiency",
            List.of("spinach", "lentil", "chickpea", "beetroot", "orange", "broccoli"),
            List.of("Folate B9", "Folic Acid"),
            9.0,
            "Leafy greens and legumes should appear daily for folate support."
        );
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
            "vitamin e deficiency",
            "Vitamin E Deficiency",
            List.of("almond", "sunflower", "spinach", "avocado", "peanut"),
            List.of("Vitamin E", "Mixed Tocopherols"),
            10.0,
            "Nuts, seeds, and avocado can help increase vitamin E intake."
        );
        registerDeficiencyProfile(
            "vitamin k deficiency",
            "Vitamin K Deficiency",
            List.of("spinach", "kale", "broccoli", "cabbage", "lettuce"),
            List.of("Vitamin K1", "K2 MK-7"),
            12.0,
            "Use more leafy greens and cruciferous vegetables for vitamin K support."
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
            "potassium deficiency",
            "Potassium Deficiency",
            List.of("banana", "potato", "spinach", "beans", "yogurt", "coconut water"),
            List.of("Potassium Citrate", "Electrolyte Potassium"),
            13.0,
            "Include potassium-rich produce daily and maintain hydration."
        );
        registerDeficiencyProfile(
            "phosphorus deficiency",
            "Phosphorus Deficiency",
            List.of("fish", "chicken", "milk", "yogurt", "lentil", "beans", "nuts"),
            List.of("Phosphorus Complex"),
            11.0,
            "Protein-rich foods typically cover phosphorus needs when meals are balanced."
        );
        registerDeficiencyProfile(
            "iodine deficiency",
            "Iodine Deficiency",
            List.of("seaweed", "fish", "shrimp", "egg", "milk", "yogurt"),
            List.of("Iodine", "Kelp Iodine"),
            9.0,
            "Use iodine-rich seafood and dairy; include iodized salt as advised."
        );
        registerDeficiencyProfile(
            "selenium deficiency",
            "Selenium Deficiency",
            List.of("tuna", "sardine", "egg", "chicken", "brown rice", "mushroom"),
            List.of("Selenium", "Selenium + Vitamin E"),
            10.0,
            "Fish, eggs, and whole grains are practical selenium sources."
        );
        registerDeficiencyProfile(
            "copper deficiency",
            "Copper Deficiency",
            List.of("cashew", "almond", "lentil", "chickpea", "mushroom", "beans"),
            List.of("Copper", "Copper + Zinc Balance"),
            10.0,
            "Nuts, seeds, and legumes can support copper repletion."
        );
        registerDeficiencyProfile(
            "manganese deficiency",
            "Manganese Deficiency",
            List.of("oats", "brown rice", "pineapple", "spinach", "nuts", "beans"),
            List.of("Manganese", "Trace Mineral Blend"),
            10.0,
            "Use whole grains and nuts regularly for manganese support."
        );
        registerDeficiencyProfile(
            "chromium deficiency",
            "Chromium Deficiency",
            List.of("broccoli", "whole wheat", "potato", "beans", "chicken", "apple"),
            List.of("Chromium Picolinate"),
            11.0,
            "Chromium-rich whole foods can support glucose metabolism."
        );
        registerDeficiencyProfile(
            "molybdenum deficiency",
            "Molybdenum Deficiency",
            List.of("lentil", "beans", "peas", "oats", "barley"),
            List.of("Molybdenum", "Trace Mineral Blend"),
            10.0,
            "Legumes and whole grains are helpful for molybdenum intake."
        );
        registerDeficiencyProfile(
            "sodium deficiency",
            "Sodium Deficiency",
            List.of("broth", "soup", "soy sauce", "cheese", "yogurt"),
            List.of("Electrolyte Sodium", "ORS"),
            9.0,
            "Use physician-guided electrolyte replacement, especially in heavy sweating."
        );
        registerDeficiencyProfile(
            "chloride deficiency",
            "Chloride Deficiency",
            List.of("broth", "soup", "tomato", "lettuce", "seaweed", "soy sauce"),
            List.of("Electrolyte Chloride", "ORS"),
            9.0,
            "Rehydrate with balanced electrolytes to support chloride levels."
        );
        registerDeficiencyProfile(
            "fluoride deficiency",
            "Fluoride Deficiency",
            List.of("tea", "fish", "seafood"),
            List.of("Fluoride (clinical guidance)"),
            8.0,
            "Use local dental guidance for fluoride support and oral health."
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

    private void seedDeficiencyInsights() {
        registerDeficiencyInsight(
            "vitamin a deficiency",
            List.of("Night vision difficulty", "Dry skin or dry eyes", "Frequent infections"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminA-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-a/"
            )
        );
        registerDeficiencyInsight(
            "vitamin b1 deficiency",
            List.of("Fatigue", "Irritability", "Numbness or tingling"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Thiamin-Consumer/",
                "https://medlineplus.gov/ency/article/000339.htm"
            )
        );
        registerDeficiencyInsight(
            "vitamin b2 deficiency",
            List.of("Cracked lips", "Sore tongue", "Skin inflammation"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Riboflavin-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-b/"
            )
        );
        registerDeficiencyInsight(
            "vitamin b3 deficiency",
            List.of("Low appetite", "Skin rash in sun exposure", "Digestive discomfort"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Niacin-Consumer/",
                "https://medlineplus.gov/ency/article/000342.htm"
            )
        );
        registerDeficiencyInsight(
            "vitamin b5 deficiency",
            List.of("Fatigue", "Headache", "Numbness in hands or feet"),
            List.of(
                "https://ods.od.nih.gov/factsheets/PantothenicAcid-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-b/"
            )
        );
        registerDeficiencyInsight(
            "vitamin b6 deficiency",
            List.of("Mouth soreness", "Low energy", "Mood changes"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminB6-Consumer/",
                "https://medlineplus.gov/ency/article/002402.htm"
            )
        );
        registerDeficiencyInsight(
            "vitamin b7 deficiency",
            List.of("Hair thinning", "Skin rash", "Low energy"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Biotin-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-b/"
            )
        );
        registerDeficiencyInsight(
            "vitamin b9 deficiency",
            List.of("Fatigue", "Pale skin", "Mouth ulcers"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Folate-Consumer/",
                "https://medlineplus.gov/folicacid.html"
            )
        );
        registerDeficiencyInsight(
            "vitamin b12 deficiency",
            List.of("Fatigue", "Numbness or tingling", "Memory or focus issues"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminB12-Consumer/",
                "https://medlineplus.gov/vitaminb12deficiencyanemia.html"
            )
        );
        registerDeficiencyInsight(
            "vitamin c deficiency",
            List.of("Easy bruising", "Bleeding gums", "Poor wound healing"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminC-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-c/"
            )
        );
        registerDeficiencyInsight(
            "vitamin d deficiency",
            List.of("Bone pain", "Muscle weakness", "Frequent illness"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminD-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-d/"
            )
        );
        registerDeficiencyInsight(
            "vitamin e deficiency",
            List.of("Muscle weakness", "Vision issues", "Nerve symptoms"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminE-Consumer/",
                "https://medlineplus.gov/ency/article/002406.htm"
            )
        );
        registerDeficiencyInsight(
            "vitamin k deficiency",
            List.of("Easy bleeding", "Easy bruising", "Slow clotting"),
            List.of(
                "https://ods.od.nih.gov/factsheets/VitaminK-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/vitamin-k/"
            )
        );
        registerDeficiencyInsight(
            "iron deficiency",
            List.of("Fatigue", "Pale skin", "Shortness of breath"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Iron-Consumer/",
                "https://medlineplus.gov/irondeficiencyanemia.html"
            )
        );
        registerDeficiencyInsight(
            "calcium deficiency",
            List.of("Muscle cramps", "Weak nails", "Bone weakness over time"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Calcium-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/calcium/"
            )
        );
        registerDeficiencyInsight(
            "magnesium deficiency",
            List.of("Muscle cramps", "Fatigue", "Tingling sensation"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Magnesium-Consumer/",
                "https://medlineplus.gov/magnesiumindiet.html"
            )
        );
        registerDeficiencyInsight(
            "zinc deficiency",
            List.of("Low immunity", "Hair thinning", "Slow wound healing"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Zinc-Consumer/",
                "https://medlineplus.gov/zincindiet.html"
            )
        );
        registerDeficiencyInsight(
            "potassium deficiency",
            List.of("Muscle weakness", "Cramps", "Irregular heartbeat"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Potassium-Consumer/",
                "https://medlineplus.gov/ency/article/000479.htm"
            )
        );
        registerDeficiencyInsight(
            "phosphorus deficiency",
            List.of("Weakness", "Bone discomfort", "Poor appetite"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Phosphorus-Consumer/",
                "https://medlineplus.gov/phosphorusindiet.html"
            )
        );
        registerDeficiencyInsight(
            "iodine deficiency",
            List.of("Tiredness", "Weight gain tendency", "Neck swelling (goiter)"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Iodine-Consumer/",
                "https://www.nhs.uk/conditions/vitamins-and-minerals/iodine/"
            )
        );
        registerDeficiencyInsight(
            "selenium deficiency",
            List.of("Low immunity", "Thyroid dysfunction signs", "Muscle weakness"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Selenium-Consumer/",
                "https://medlineplus.gov/seleniumindiet.html"
            )
        );
        registerDeficiencyInsight(
            "copper deficiency",
            List.of("Fatigue", "Frequent infections", "Low blood counts"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Copper-Consumer/",
                "https://medlineplus.gov/copperindiet.html"
            )
        );
        registerDeficiencyInsight(
            "manganese deficiency",
            List.of("Reduced growth", "Bone issues", "Carb metabolism changes"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Manganese-Consumer/",
                "https://medlineplus.gov/manganeseindiet.html"
            )
        );
        registerDeficiencyInsight(
            "chromium deficiency",
            List.of("Blood sugar instability", "Fatigue", "Low exercise tolerance"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Chromium-Consumer/",
                "https://medlineplus.gov/chromiumindiet.html"
            )
        );
        registerDeficiencyInsight(
            "molybdenum deficiency",
            List.of("Rare: neurological symptoms", "Rapid breathing", "Mental confusion"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Molybdenum-Consumer/",
                "https://medlineplus.gov/molybdenumindiet.html"
            )
        );
        registerDeficiencyInsight(
            "fluoride deficiency",
            List.of("Tooth enamel weakness", "Dental caries risk", "Tooth sensitivity"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Fluoride-Consumer/",
                "https://medlineplus.gov/fluoride.html"
            )
        );
        registerDeficiencyInsight(
            "sodium deficiency",
            List.of("Nausea", "Headache", "Confusion or weakness"),
            List.of(
                "https://medlineplus.gov/ency/article/000394.htm",
                "https://www.cdc.gov/salt/about/index.html"
            )
        );
        registerDeficiencyInsight(
            "chloride deficiency",
            List.of("Weakness", "Loss of appetite", "Dehydration signs"),
            List.of(
                "https://medlineplus.gov/lab-tests/chloride-blood-test/",
                "https://www.mountsinai.org/health-library/tests/chloride-blood-test"
            )
        );
        registerDeficiencyInsight(
            "protein deficiency",
            List.of("Muscle loss", "Slow wound healing", "Frequent hunger"),
            List.of(
                "https://www.hsph.harvard.edu/nutritionsource/what-should-you-eat/protein/",
                "https://medlineplus.gov/ency/article/000404.htm"
            )
        );
        registerDeficiencyInsight(
            "fiber deficiency",
            List.of("Constipation", "Poor satiety", "Blood sugar fluctuations"),
            List.of(
                "https://www.hsph.harvard.edu/nutritionsource/carbohydrates/fiber/",
                "https://medlineplus.gov/ency/article/002136.htm"
            )
        );
        registerDeficiencyInsight(
            "omega 3 deficiency",
            List.of("Dry skin", "Low concentration", "Mood changes"),
            List.of(
                "https://ods.od.nih.gov/factsheets/Omega3FattyAcids-Consumer/",
                "https://medlineplus.gov/ency/article/002451.htm"
            )
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

    private void registerDeficiencyInsight(
        String key,
        List<String> symptoms,
        List<String> sources
    ) {
        deficiencySymptoms.put(key, symptoms);
        deficiencySources.put(key, sources);
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

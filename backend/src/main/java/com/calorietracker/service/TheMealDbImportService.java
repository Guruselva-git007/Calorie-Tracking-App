package com.calorietracker.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.Dish;
import com.calorietracker.entity.DishComponent;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.DishComponentRepository;
import com.calorietracker.repository.DishRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TheMealDbImportService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(24);

    private final DishRepository dishRepository;
    private final DishComponentRepository dishComponentRepository;
    private final IngredientService ingredientService;
    private final FoodMetadataService foodMetadataService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TheMealDbImportService(
        DishRepository dishRepository,
        DishComponentRepository dishComponentRepository,
        IngredientService ingredientService,
        FoodMetadataService foodMetadataService,
        ObjectMapper objectMapper
    ) {
        this.dishRepository = dishRepository;
        this.dishComponentRepository = dishComponentRepository;
        this.ingredientService = ingredientService;
        this.foodMetadataService = foodMetadataService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    public Map<String, Integer> importByCuisineLabels(List<String> cuisineLabels, int maxPerCuisine) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int total = 0;

        List<String> targets = cuisineLabels == null || cuisineLabels.isEmpty()
            ? List.of(
                "indian",
                "chinese",
                "indo chinese",
                "european",
                "mediterranean",
                "african",
                "western",
                "eastern",
                "northern",
                "southern"
            )
            : cuisineLabels;

        int cap = Math.max(1, maxPerCuisine);

        for (String label : targets) {
            String normalizedLabel = label.trim();
            if (normalizedLabel.isBlank()) {
                continue;
            }

            int importedForLabel = 0;
            for (String area : resolveAreas(normalizedLabel)) {
                if (importedForLabel >= cap) {
                    break;
                }
                importedForLabel += importArea(area, normalizedLabel, cap - importedForLabel);
            }

            result.put(toTitleCase(normalizedLabel), importedForLabel);
            total += importedForLabel;
        }

        result.put("TOTAL", total);
        return result;
    }

    public Map<String, Integer> importAllAreas(int maxPerArea) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int total = 0;
        int cap = Math.max(1, maxPerArea);

        List<String> areas = fetchAvailableAreas();
        for (String area : areas) {
            if (!StringUtils.hasText(area)) {
                continue;
            }
            int imported = importArea(area, area, cap);
            result.put(area, imported);
            total += imported;
        }

        result.put("TOTAL", total);
        return result;
    }

    private int importArea(String area, String targetCuisine, int limit) {
        if (limit <= 0) {
            return 0;
        }

        JsonNode meals = fetchMealsByArea(area);
        if (meals == null || !meals.isArray()) {
            return 0;
        }

        int imported = 0;

        for (JsonNode mealRef : meals) {
            if (imported >= limit) {
                break;
            }

            String mealId = mealRef.path("idMeal").asText("").trim();
            if (!StringUtils.hasText(mealId)) {
                continue;
            }

            String quickMealName = mealRef.path("strMeal").asText("").trim();
            if (StringUtils.hasText(quickMealName) && dishRepository.existsByNameIgnoreCase(quickMealName)) {
                continue;
            }

            JsonNode meal = fetchMealById(mealId);
            if (meal == null || meal.isMissingNode()) {
                continue;
            }

            String mealName = meal.path("strMeal").asText("").trim();
            if (!StringUtils.hasText(mealName) || dishRepository.existsByNameIgnoreCase(mealName)) {
                continue;
            }

            List<ComponentPlan> components = extractComponents(meal, targetCuisine);
            if (components.isEmpty()) {
                continue;
            }

            String description = meal.path("strInstructions").asText("Imported from TheMealDB").trim();
            if (description.length() > 500) {
                description = description.substring(0, 500);
            }

            Dish dish = new Dish(
                mealName,
                toTitleCase(targetCuisine),
                description,
                false,
                "THE_MEAL_DB"
            );
            dish.setImageUrl(
                foodMetadataService.resolveDishImageUrl(
                    meal.path("strMealThumb").asText(""),
                    mealName,
                    targetCuisine
                )
            );
            Dish savedDish = dishRepository.save(dish);

            for (ComponentPlan component : components) {
                dishComponentRepository.save(new DishComponent(savedDish, component.ingredient(), component.grams()));
            }

            imported++;
        }

        return imported;
    }

    private JsonNode fetchMealsByArea(String area) {
        try {
            String encoded = URLEncoder.encode(area, StandardCharsets.UTF_8);
            String url = "https://www.themealdb.com/api/json/v1/1/filter.php?a=" + encoded;
            JsonNode root = fetchJson(url);
            return root.path("meals");
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode fetchMealById(String mealId) {
        try {
            String encoded = URLEncoder.encode(mealId, StandardCharsets.UTF_8);
            String url = "https://www.themealdb.com/api/json/v1/1/lookup.php?i=" + encoded;
            JsonNode root = fetchJson(url);
            JsonNode meals = root.path("meals");
            if (meals.isArray() && meals.size() > 0) {
                return meals.get(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<String> fetchAvailableAreas() {
        try {
            JsonNode root = fetchJson("https://www.themealdb.com/api/json/v1/1/list.php?a=list");
            JsonNode meals = root.path("meals");
            if (!meals.isArray()) {
                return List.of();
            }

            return toMealStream(meals)
                .map(item -> item.path("strArea").asText("").trim())
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", "calorie-tracker-cuisine-import")
            .build();

        HttpResponse<String> response = sendWithHardTimeout(request);
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IOException("TheMealDB request timed out.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            if (cause instanceof InterruptedException interruptedEx) {
                Thread.currentThread().interrupt();
                throw interruptedEx;
            }
            throw new IOException("TheMealDB request failed.", cause);
        }
    }

    private java.util.stream.Stream<JsonNode> toMealStream(JsonNode meals) {
        List<JsonNode> rows = new ArrayList<>();
        meals.forEach(rows::add);
        return rows.stream();
    }

    private List<ComponentPlan> extractComponents(JsonNode meal, String cuisineLabel) {
        List<ComponentPlan> rows = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            String ingredientName = meal.path("strIngredient" + i).asText("").trim();
            if (!StringUtils.hasText(ingredientName)) {
                continue;
            }

            String measure = meal.path("strMeasure" + i).asText("").trim();
            double grams = estimateGrams(measure);
            Ingredient ingredient = resolveOrCreateIngredient(ingredientName, cuisineLabel);
            rows.add(new ComponentPlan(ingredient, grams));
        }

        return rows;
    }

    private Ingredient resolveOrCreateIngredient(String ingredientName, String cuisineLabel) {
        return ingredientService.findByNameIgnoreCase(ingredientName)
            .orElseGet(() -> {
                IngredientCategory category = guessCategory(ingredientName);
                double calories = defaultCaloriesByCategory(category);

                Ingredient ingredient = new Ingredient(
                    toTitleCase(ingredientName),
                    category,
                    toTitleCase(cuisineLabel),
                    CalorieMath.round(calories),
                    "100 g",
                    false,
                    "THE_MEAL_DB_ESTIMATED"
                );
                return ingredientService.save(ingredient);
            });
    }

    private List<String> resolveAreas(String label) {
        String key = label.toLowerCase(Locale.ROOT).trim();

        return switch (key) {
            case "indian" -> List.of("Indian");
            case "chinese" -> List.of("Chinese");
            case "indo chinese", "indochinese" -> List.of("Chinese", "Thai", "Malaysian");
            case "european" -> List.of("French", "Italian", "Spanish", "British", "Dutch", "Polish", "Russian", "Croatian", "Portuguese");
            case "mediterranean", "mediterrian" -> List.of("Greek", "Italian", "Spanish", "Moroccan", "Turkish");
            case "african" -> List.of("Moroccan", "Egyptian", "Kenyan", "Tunisian");
            case "western" -> List.of("American", "British", "French", "Italian", "Canadian");
            case "eastern" -> List.of("Chinese", "Japanese", "Thai", "Vietnamese", "Malaysian");
            case "northern" -> List.of("British", "Russian", "Polish", "Dutch", "Ukrainian");
            case "southern" -> List.of("Indian", "Greek", "Italian", "Mexican", "Spanish");
            default -> List.of(toTitleCase(label));
        };
    }

    private IngredientCategory guessCategory(String name) {
        String value = name.toLowerCase(Locale.ROOT);

        if (containsAny(value, "oil", "ghee", "butter")) {
            return IngredientCategory.OIL;
        }
        if (containsAny(value, "milk", "cheese", "cream", "yogurt", "paneer")) {
            return IngredientCategory.DAIRY;
        }
        if (containsAny(value, "chicken", "beef", "lamb", "mutton", "pork", "duck", "sausage", "bacon", "ham")) {
            return IngredientCategory.MEAT;
        }
        if (containsAny(value, "fish", "salmon", "tuna", "prawn", "shrimp", "crab", "mussel", "oyster", "squid", "octopus")) {
            return IngredientCategory.SEAFOOD;
        }
        if (containsAny(value, "rice")) {
            return IngredientCategory.RICE;
        }
        if (containsAny(value, "apple", "banana", "orange", "mango", "berry", "grape", "fruit")) {
            return IngredientCategory.FRUIT;
        }
        if (containsAny(value, "carrot", "onion", "garlic", "tomato", "pepper", "spinach", "broccoli", "cabbage", "potato", "vegetable")) {
            return IngredientCategory.VEGETABLE;
        }
        if (containsAny(value, "snack", "chips", "cracker", "mixture", "mix")) {
            return IngredientCategory.SNACK;
        }
        if (containsAny(value, "lentil", "bean", "chickpea", "tofu")) {
            return IngredientCategory.LEGUME;
        }

        return IngredientCategory.OTHER;
    }

    private boolean containsAny(String value, String... options) {
        for (String option : options) {
            if (value.contains(option)) {
                return true;
            }
        }
        return false;
    }

    private double defaultCaloriesByCategory(IngredientCategory category) {
        return switch (category) {
            case OIL -> 884.0;
            case DAIRY -> 265.0;
            case MEAT -> 220.0;
            case SEAFOOD -> 150.0;
            case RICE -> 130.0;
            case FRUIT -> 60.0;
            case VEGETABLE -> 35.0;
            case SNACK -> 450.0;
            case LEGUME -> 140.0;
            default -> 120.0;
        };
    }

    private double estimateGrams(String measureText) {
        String measure = measureText == null ? "" : measureText.toLowerCase(Locale.ROOT).trim();
        if (!StringUtils.hasText(measure)) {
            return 70.0;
        }

        double qty = parseNumber(measure);
        if (qty <= 0) {
            qty = 1.0;
        }

        if (measure.contains("kg")) {
            return CalorieMath.round(qty * 1000.0);
        }
        if (measure.contains("g")) {
            return CalorieMath.round(qty);
        }
        if (measure.contains("ml")) {
            return CalorieMath.round(qty);
        }
        if (measure.contains(" l") || measure.endsWith("l")) {
            return CalorieMath.round(qty * 1000.0);
        }
        if (measure.contains("tbsp") || measure.contains("tablespoon")) {
            return CalorieMath.round(qty * 15.0);
        }
        if (measure.contains("tsp") || measure.contains("teaspoon")) {
            return CalorieMath.round(qty * 5.0);
        }
        if (measure.contains("cup")) {
            return CalorieMath.round(qty * 120.0);
        }
        if (measure.contains("oz")) {
            return CalorieMath.round(qty * 28.35);
        }
        if (measure.contains("lb")) {
            return CalorieMath.round(qty * 453.59);
        }
        if (measure.contains("slice")) {
            return CalorieMath.round(qty * 30.0);
        }
        if (measure.contains("clove")) {
            return CalorieMath.round(qty * 5.0);
        }
        if (containsAny(measure, "piece", "pieces", "fillet", "whole", "pc")) {
            return CalorieMath.round(qty * 80.0);
        }

        return CalorieMath.round(qty * 70.0);
    }

    private double parseNumber(String text) {
        if (text.contains("1/2")) {
            return 0.5;
        }
        if (text.contains("1/4")) {
            return 0.25;
        }
        if (text.contains("3/4")) {
            return 0.75;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 1.0;
            }
        }

        return 1.0;
    }

    private String toTitleCase(String value) {
        String[] parts = value.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        return builder.toString();
    }

    private record ComponentPlan(Ingredient ingredient, double grams) {
    }
}

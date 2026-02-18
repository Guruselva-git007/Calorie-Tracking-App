package com.calorietracker.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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
public class DummyJsonRecipeImportService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(24);

    private final DishRepository dishRepository;
    private final DishComponentRepository dishComponentRepository;
    private final IngredientService ingredientService;
    private final FoodMetadataService foodMetadataService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DummyJsonRecipeImportService(
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

    public Map<String, Integer> importRecipes(int pageSize, int maxRecipes) {
        int safePageSize = Math.max(10, Math.min(pageSize, 100));
        int safeMaxRecipes = maxRecipes <= 0 ? 500 : Math.min(maxRecipes, 3000);

        int totalProcessed = 0;
        int totalImportedDishes = 0;
        int totalNewIngredients = 0;
        int totalSkippedExisting = 0;

        int skip = 0;
        int remoteTotal = Integer.MAX_VALUE;
        Map<String, Ingredient> ingredientCache = new LinkedHashMap<>();

        while (skip < remoteTotal && totalProcessed < safeMaxRecipes) {
            JsonNode root = fetchRecipesPage(skip, safePageSize);
            if (root == null || root.isMissingNode()) {
                break;
            }

            JsonNode recipes = root.path("recipes");
            if (!recipes.isArray() || recipes.isEmpty()) {
                break;
            }

            int reportedTotal = root.path("total").asInt(0);
            if (reportedTotal > 0) {
                remoteTotal = Math.min(reportedTotal, safeMaxRecipes);
            }

            for (JsonNode recipe : recipes) {
                if (totalProcessed >= safeMaxRecipes) {
                    break;
                }
                totalProcessed++;

                String dishName = sanitizeDishName(recipe.path("name").asText(""));
                if (!StringUtils.hasText(dishName)) {
                    continue;
                }

                if (dishRepository.existsByNameIgnoreCase(dishName)) {
                    totalSkippedExisting++;
                    continue;
                }

                String cuisine = sanitizeCuisine(recipe.path("cuisine").asText(""));
                List<String> ingredientNames = extractIngredientNames(recipe.path("ingredients"));
                if (ingredientNames.isEmpty()) {
                    continue;
                }

                List<ComponentPlan> plans = resolveIngredientPlans(ingredientNames, cuisine, ingredientCache);
                if (plans.isEmpty()) {
                    continue;
                }

                double caloriesPerServing = parsePositive(recipe.path("caloriesPerServing").asDouble(0.0), 280.0);
                String description = buildDescription(recipe, caloriesPerServing);

                Dish dish = new Dish(
                    dishName,
                    cuisine,
                    description,
                    false,
                    "DUMMYJSON"
                );
                dish.setImageUrl(
                    foodMetadataService.resolveDishImageUrl(
                        recipe.path("image").asText(""),
                        dishName,
                        cuisine
                    )
                );
                Dish savedDish = dishRepository.save(dish);

                for (ComponentPlan plan : plans) {
                    dishComponentRepository.save(new DishComponent(savedDish, plan.ingredient(), plan.grams()));
                    if (plan.createdNewIngredient()) {
                        totalNewIngredients++;
                    }
                }

                totalImportedDishes++;
            }

            skip += recipes.size();
            if (recipes.size() < safePageSize) {
                break;
            }
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("TOTAL_IMPORTED", totalImportedDishes);
        result.put("NEW_INGREDIENTS", totalNewIngredients);
        result.put("PROCESSED_RECIPES", totalProcessed);
        result.put("SKIPPED_EXISTING", totalSkippedExisting);
        return result;
    }

    private JsonNode fetchRecipesPage(int skip, int limit) {
        String url = String.format(
            Locale.ROOT,
            "https://dummyjson.com/recipes?limit=%d&skip=%d",
            Math.max(1, limit),
            Math.max(0, skip)
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "calorie-tracker-dummyjson-import")
                .build();

            HttpResponse<String> response = sendWithHardTimeout(request);
            if (response.statusCode() >= 400) {
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ignored) {
            return null;
        }
    }

    private List<String> extractIngredientNames(JsonNode ingredientsNode) {
        if (ingredientsNode == null || !ingredientsNode.isArray()) {
            return List.of();
        }

        return toStreamList(ingredientsNode).stream()
            .map(this::normalizeIngredientName)
            .filter(StringUtils::hasText)
            .distinct()
            .limit(24)
            .collect(Collectors.toList());
    }

    private List<ComponentPlan> resolveIngredientPlans(
        List<String> ingredientNames,
        String cuisine,
        Map<String, Ingredient> ingredientCache
    ) {
        List<ComponentPlan> plans = new ArrayList<>();
        int ingredientCount = Math.max(1, ingredientNames.size());

        for (String ingredientName : ingredientNames) {
            String normalized = ingredientName.trim().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }

            Ingredient ingredient = ingredientCache.get(normalized);
            boolean createdNew = false;

            if (ingredient == null) {
                Optional<Ingredient> existing = ingredientService.findByNameIgnoreCase(ingredientName);
                if (existing.isPresent()) {
                    ingredient = existing.get();
                } else {
                    IngredientCategory category = guessCategory(ingredientName);
                    Ingredient created = new Ingredient(
                        ingredientName,
                        category,
                        cuisine,
                        defaultCaloriesByCategory(category),
                        "100 g",
                        false,
                        "DUMMYJSON_ESTIMATED"
                    );
                    ingredient = ingredientService.save(created);
                    createdNew = true;
                }
                ingredientCache.put(normalized, ingredient);
            }

            double grams = estimateGrams(ingredient.getCategory(), ingredientName, ingredientCount);
            plans.add(new ComponentPlan(ingredient, grams, createdNew));
        }

        Map<Long, ComponentPlan> merged = new LinkedHashMap<>();
        for (ComponentPlan plan : plans) {
            ComponentPlan previous = merged.get(plan.ingredient().getId());
            if (previous == null) {
                merged.put(plan.ingredient().getId(), plan);
                continue;
            }
            merged.put(
                plan.ingredient().getId(),
                new ComponentPlan(
                    plan.ingredient(),
                    CalorieMath.round(previous.grams() + plan.grams()),
                    previous.createdNewIngredient() || plan.createdNewIngredient()
                )
            );
        }

        return new ArrayList<>(merged.values());
    }

    private String sanitizeDishName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String cleaned = name.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 170) {
            cleaned = cleaned.substring(0, 170);
        }
        return cleaned;
    }

    private String sanitizeCuisine(String cuisine) {
        if (!StringUtils.hasText(cuisine)) {
            return "Global";
        }
        String cleaned = cuisine.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 110) {
            cleaned = cleaned.substring(0, 110);
        }
        return toTitleCase(cleaned);
    }

    private String normalizeIngredientName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }

        String cleaned = raw;
        cleaned = cleaned.replaceAll("\\([^)]*\\)", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(to taste|for garnish|optional|as needed|as required)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(cups?|cup|tbsp|tablespoons?|tsp|teaspoons?|grams?|g|kg|ml|l|oz|pounds?|lb|cloves?|slices?)\\b", " ");
        cleaned = cleaned.replaceAll("^[0-9\\s./¼½¾⅓⅔-]+", " ");
        cleaned = cleaned.replaceAll("[^a-zA-Z\\s-]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (!StringUtils.hasText(cleaned)) {
            return "";
        }

        if (cleaned.length() > 170) {
            cleaned = cleaned.substring(0, 170);
        }
        return toTitleCase(cleaned);
    }

    private String buildDescription(JsonNode recipe, double caloriesPerServing) {
        List<String> instructions = toStreamList(recipe.path("instructions"));
        String instructionSummary = instructions.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .limit(2)
            .collect(Collectors.joining(" "));

        String difficulty = recipe.path("difficulty").asText("").trim();
        String prep = recipe.path("prepTimeMinutes").asText("").trim();
        String cook = recipe.path("cookTimeMinutes").asText("").trim();

        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(difficulty)) {
            parts.add(difficulty);
        }
        if (StringUtils.hasText(prep) || StringUtils.hasText(cook)) {
            String prepText = StringUtils.hasText(prep) ? prep + "m prep" : "";
            String cookText = StringUtils.hasText(cook) ? cook + "m cook" : "";
            parts.add((prepText + " " + cookText).trim());
        }
        parts.add("~" + Math.round(caloriesPerServing) + " cal/serving");
        if (StringUtils.hasText(instructionSummary)) {
            parts.add(instructionSummary);
        }

        String description = String.join(" · ", parts);
        if (description.length() > 500) {
            return description.substring(0, 500);
        }
        return description;
    }

    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IOException("DummyJSON request timed out.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            if (cause instanceof InterruptedException interruptedEx) {
                Thread.currentThread().interrupt();
                throw interruptedEx;
            }
            throw new IOException("DummyJSON request failed.", cause);
        }
    }

    private List<String> toStreamList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText("")));
        return values;
    }

    private IngredientCategory guessCategory(String ingredientName) {
        String value = ingredientName.toLowerCase(Locale.ROOT);

        if (containsAny(value, "oil", "ghee", "butter")) {
            return IngredientCategory.OIL;
        }
        if (containsAny(value, "milk", "cheese", "cream", "yogurt", "paneer", "ice cream")) {
            return IngredientCategory.DAIRY;
        }
        if (containsAny(value, "chicken", "beef", "lamb", "mutton", "pork", "duck", "turkey", "sausage", "bacon", "ham")) {
            return IngredientCategory.MEAT;
        }
        if (containsAny(value, "fish", "salmon", "tuna", "prawn", "shrimp", "crab", "mussel", "oyster", "squid", "octopus")) {
            return IngredientCategory.SEAFOOD;
        }
        if (containsAny(value, "rice", "risotto")) {
            return IngredientCategory.RICE;
        }
        if (containsAny(value, "juice", "smoothie")) {
            return IngredientCategory.JUICE;
        }
        if (containsAny(value, "flour", "pasta", "noodle", "bread", "oat", "corn", "wheat", "barley", "quinoa")) {
            return IngredientCategory.GRAIN;
        }
        if (containsAny(value, "bean", "lentil", "chickpea", "pea", "tofu")) {
            return IngredientCategory.LEGUME;
        }
        if (containsAny(value, "pepper", "cumin", "turmeric", "chili", "paprika", "masala", "spice", "herb")) {
            return IngredientCategory.SPICE;
        }
        if (containsAny(value, "sauce", "ketchup", "mayo", "mustard", "soy", "vinegar")) {
            return IngredientCategory.SAUCE;
        }
        if (containsAny(value, "cake", "cookie", "biscuit", "pastry", "brownie", "snack", "chips", "cracker")) {
            return IngredientCategory.SNACK;
        }
        if (containsAny(value, "apple", "banana", "orange", "mango", "berry", "grape", "pineapple", "melon", "fruit")) {
            return IngredientCategory.FRUIT;
        }
        if (containsAny(value, "carrot", "onion", "garlic", "tomato", "pepper", "spinach", "broccoli", "cabbage", "potato", "mushroom", "vegetable")) {
            return IngredientCategory.VEGETABLE;
        }
        if (containsAny(value, "tea", "coffee", "water")) {
            return IngredientCategory.BEVERAGE;
        }

        return IngredientCategory.OTHER;
    }

    private boolean containsAny(String value, String... options) {
        return Arrays.stream(options).anyMatch(value::contains);
    }

    private double defaultCaloriesByCategory(IngredientCategory category) {
        return switch (category) {
            case OIL -> 884.0;
            case DAIRY -> 265.0;
            case MEAT -> 220.0;
            case SEAFOOD -> 150.0;
            case RICE -> 130.0;
            case JUICE -> 48.0;
            case FRUIT -> 60.0;
            case VEGETABLE -> 35.0;
            case LEGUME -> 145.0;
            case GRAIN -> 350.0;
            case SPICE -> 280.0;
            case SAUCE -> 180.0;
            case SNACK -> 430.0;
            case BEVERAGE -> 18.0;
            default -> 120.0;
        };
    }

    private double estimateGrams(IngredientCategory category, String ingredientName, int ingredientCount) {
        String value = ingredientName.toLowerCase(Locale.ROOT);
        if (containsAny(value, "salt", "pepper", "spice", "powder")) {
            return 4.0;
        }

        double base = switch (category) {
            case OIL, SAUCE -> 14.0;
            case SPICE -> 5.0;
            case MEAT, SEAFOOD -> 90.0;
            case RICE, GRAIN, LEGUME -> 70.0;
            case SNACK -> 35.0;
            case DAIRY -> 55.0;
            case FRUIT, VEGETABLE -> 60.0;
            default -> 50.0;
        };

        double scale = 1.0;
        if (ingredientCount >= 10) {
            scale = 0.72;
        } else if (ingredientCount >= 7) {
            scale = 0.85;
        } else if (ingredientCount <= 3) {
            scale = 1.15;
        }

        return CalorieMath.round(base * scale);
    }

    private double parsePositive(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0) {
            return fallback;
        }
        return value;
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

    private record ComponentPlan(Ingredient ingredient, double grams, boolean createdNewIngredient) {
    }
}

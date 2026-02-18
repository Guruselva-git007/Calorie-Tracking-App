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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.IngredientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class OpenFoodFactsImportService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(24);
    private static final int MAX_CONSECUTIVE_REQUEST_FAILURES = 6;

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenFoodFactsImportService(
        IngredientRepository ingredientRepository,
        FoodMetadataService foodMetadataService,
        ObjectMapper objectMapper
    ) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public int importFoods(List<String> countries, int pageCount, int pageSize) {
        int imported = 0;
        int consecutiveRequestFailures = 0;
        boolean abortSource = false;
        List<String> normalizedCountries = countries == null || countries.isEmpty()
            ? List.of("united-states", "india", "japan", "mexico", "italy", "brazil")
            : countries;
        Set<String> existingNames = ingredientRepository.findAll().stream()
            .map(ingredient -> normalizeName(ingredient.getName()))
            .collect(Collectors.toCollection(HashSet::new));
        List<Ingredient> pendingSaves = new ArrayList<>();

        for (String country : normalizedCountries) {
            if (abortSource) {
                break;
            }
            String cleanCountry = country.trim();
            if (cleanCountry.isEmpty()) {
                continue;
            }

            for (int page = 1; page <= Math.max(1, pageCount); page++) {
                String encodedCountry = URLEncoder.encode(cleanCountry, StandardCharsets.UTF_8);
                String url = String.format(
                    "https://world.openfoodfacts.org/cgi/search.pl?action=process&json=1&page=%d&page_size=%d&tagtype_0=countries&tag_contains_0=contains&tag_0=%s&fields=product_name,nutriments,categories_tags,categories,image_url,image_front_url,image_front_small_url",
                    page,
                    Math.max(20, pageSize),
                    encodedCountry
                );

                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "calorie-tracker-importer")
                        .build();

                    HttpResponse<String> response = sendWithHardTimeout(request);
                    if (response.statusCode() >= 400) {
                        consecutiveRequestFailures++;
                        if (consecutiveRequestFailures >= MAX_CONSECUTIVE_REQUEST_FAILURES) {
                            abortSource = true;
                            break;
                        }
                        continue;
                    }
                    consecutiveRequestFailures = 0;

                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode products = root.path("products");
                    if (!products.isArray()) {
                        continue;
                    }

                    for (JsonNode product : products) {
                        String name = product.path("product_name").asText("").trim();
                        if (name.length() < 3 || name.length() > 170) {
                            continue;
                        }

                        JsonNode nutriments = product.path("nutriments");
                        double calories = extractCalories(nutriments);
                        if (calories <= 0 || calories > 900) {
                            continue;
                        }

                        String normalizedName = normalizeName(name);
                        if (!existingNames.add(normalizedName)) {
                            continue;
                        }

                        Ingredient ingredient = new Ingredient(
                            name,
                            guessCategory(product.path("categories_tags"), product.path("categories").asText("")),
                            toTitleCase(cleanCountry.replace('-', ' ')),
                            CalorieMath.round(calories),
                            "100 g",
                            false,
                            "OPEN_FOOD_FACTS"
                        );
                        ingredient.setProteinPer100g(extractNutriment(nutriments, "proteins_100g", "proteins"));
                        ingredient.setCarbsPer100g(extractNutriment(nutriments, "carbohydrates_100g", "carbohydrates"));
                        ingredient.setFatsPer100g(extractNutriment(nutriments, "fat_100g", "fat"));
                        ingredient.setFiberPer100g(extractNutriment(nutriments, "fiber_100g", "fiber"));
                        ingredient.setImageUrl(
                            foodMetadataService.sanitizeImageUrl(
                                firstText(
                                    product.path("image_front_small_url").asText(""),
                                    product.path("image_front_url").asText(""),
                                    product.path("image_url").asText("")
                                )
                            )
                        );
                        ingredient.setRegionalAvailability(
                            foodMetadataService.toCsv(List.of("Global", toTitleCase(cleanCountry.replace('-', ' '))))
                        );
                        foodMetadataService.applyDefaults(ingredient);
                        pendingSaves.add(ingredient);

                        if (pendingSaves.size() >= 120) {
                            imported += flushPending(pendingSaves, existingNames);
                        }
                    }

                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    abortSource = true;
                    break;
                } catch (IOException ignored) {
                    consecutiveRequestFailures++;
                    if (consecutiveRequestFailures >= MAX_CONSECUTIVE_REQUEST_FAILURES) {
                        abortSource = true;
                        break;
                    }
                    // Import is best-effort; failures on one source/page should not break the app.
                }
            }
        }

        imported += flushPending(pendingSaves, existingNames);

        return imported;
    }

    public OptionalDouble lookupCaloriesBySearchTerm(String searchTerm) {
        String cleanTerm = searchTerm == null ? "" : searchTerm.trim();
        if (cleanTerm.length() < 2) {
            return OptionalDouble.empty();
        }

        try {
            String encoded = URLEncoder.encode(cleanTerm, StandardCharsets.UTF_8);
            String url = String.format(
                "https://world.openfoodfacts.org/cgi/search.pl?action=process&json=1&page=1&page_size=12&search_terms=%s&fields=nutriments,product_name",
                encoded
            );

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "calorie-tracker-lookup")
                .build();

            HttpResponse<String> response = sendWithHardTimeout(request);
            if (response.statusCode() >= 400) {
                return OptionalDouble.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode products = root.path("products");
            if (!products.isArray()) {
                return OptionalDouble.empty();
            }

            double total = 0.0;
            int count = 0;
            for (JsonNode product : products) {
                double calories = extractCalories(product.path("nutriments"));
                if (calories > 0 && calories <= 900) {
                    total += calories;
                    count++;
                }
            }

            if (count == 0) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(CalorieMath.round(total / count));
        } catch (IOException | InterruptedException ignored) {
            return OptionalDouble.empty();
        }
    }

    private double extractCalories(JsonNode nutriments) {
        if (nutriments == null || nutriments.isMissingNode()) {
            return -1;
        }

        String[] keys = {
            "energy-kcal_100g",
            "energy-kcal",
            "energy-kcal_serving",
            "energy-kcal_value"
        };

        for (String key : keys) {
            if (nutriments.has(key) && nutriments.get(key).isNumber()) {
                return nutriments.get(key).asDouble();
            }
            if (nutriments.has(key) && nutriments.get(key).isTextual()) {
                try {
                    return Double.parseDouble(nutriments.get(key).asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private Double extractNutriment(JsonNode nutriments, String... keys) {
        if (nutriments == null || nutriments.isMissingNode()) {
            return null;
        }

        for (String key : keys) {
            JsonNode node = nutriments.get(key);
            if (node == null || node.isMissingNode()) {
                continue;
            }

            if (node.isNumber()) {
                double value = node.asDouble();
                if (value >= 0 && value <= 100) {
                    return CalorieMath.round(value);
                }
                continue;
            }

            if (node.isTextual()) {
                try {
                    double value = Double.parseDouble(node.asText());
                    if (value >= 0 && value <= 100) {
                        return CalorieMath.round(value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private IngredientCategory guessCategory(JsonNode tagsNode, String categoriesText) {
        List<String> tags = new ArrayList<>();
        if (tagsNode != null && tagsNode.isArray()) {
            tagsNode.forEach(node -> tags.add(node.asText("").toLowerCase(Locale.ROOT)));
        }

        String merged = categoriesText == null ? "" : categoriesText.toLowerCase(Locale.ROOT);
        merged += " " + String.join(" ", tags);

        if (containsAny(merged, "fruit", "berries", "citrus")) {
            return IngredientCategory.FRUIT;
        }
        if (containsAny(merged, "vegetable", "veg", "salad")) {
            return IngredientCategory.VEGETABLE;
        }
        if (containsAny(merged, "juice", "smoothie")) {
            return IngredientCategory.JUICE;
        }
        if (containsAny(merged, "rice")) {
            return IngredientCategory.RICE;
        }
        if (containsAny(merged, "meat", "chicken", "beef", "pork", "lamb", "sausage")) {
            return IngredientCategory.MEAT;
        }
        if (containsAny(merged, "fish", "seafood", "shrimp", "tuna", "salmon")) {
            return IngredientCategory.SEAFOOD;
        }
        if (containsAny(merged, "snack", "chips", "cookie", "cracker", "bar")) {
            return IngredientCategory.SNACK;
        }
        if (containsAny(merged, "bean", "lentil", "chickpea", "tofu")) {
            return IngredientCategory.LEGUME;
        }
        if (containsAny(merged, "milk", "cheese", "yogurt", "dairy")) {
            return IngredientCategory.DAIRY;
        }
        if (containsAny(merged, "sauce")) {
            return IngredientCategory.SAUCE;
        }
        return IngredientCategory.OTHER;
    }

    private boolean containsAny(String target, String... keys) {
        return Arrays.stream(keys).anyMatch(target::contains);
    }

    private String toTitleCase(String text) {
        String[] parts = text.trim().split("\\s+");
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

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IOException("OpenFoodFacts request timed out.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            if (cause instanceof InterruptedException interruptedEx) {
                Thread.currentThread().interrupt();
                throw interruptedEx;
            }
            throw new IOException("OpenFoodFacts request failed.", cause);
        }
    }

    private int flushPending(List<Ingredient> pendingSaves, Set<String> existingNames) {
        int inserted = 0;
        for (Ingredient ingredient : pendingSaves) {
            if (ingredientRepository.existsByNameIgnoreCase(ingredient.getName())) {
                existingNames.add(normalizeName(ingredient.getName()));
                continue;
            }
            try {
                ingredientRepository.save(ingredient);
                inserted++;
            } catch (DataIntegrityViolationException ignored) {
                existingNames.add(normalizeName(ingredient.getName()));
            }
        }
        pendingSaves.clear();
        return inserted;
    }
}

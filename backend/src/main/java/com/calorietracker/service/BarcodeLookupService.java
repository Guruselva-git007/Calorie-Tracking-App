package com.calorietracker.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.BarcodeLookupResponse;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.repository.IngredientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class BarcodeLookupService {

    private static final Pattern BARCODE_PATTERN = Pattern.compile("[0-9A-Za-z\\-]{6,32}");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(14);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(18);

    private final ObjectMapper objectMapper;
    private final FoodMetadataService foodMetadataService;
    private final IngredientRepository ingredientRepository;
    private final HttpClient httpClient;

    public BarcodeLookupService(
        ObjectMapper objectMapper,
        FoodMetadataService foodMetadataService,
        IngredientRepository ingredientRepository
    ) {
        this.objectMapper = objectMapper;
        this.foodMetadataService = foodMetadataService;
        this.ingredientRepository = ingredientRepository;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    public BarcodeLookupResponse lookupByBarcode(String rawCode) {
        String code = sanitizeBarcode(rawCode);

        BarcodeLookupResponse result = new BarcodeLookupResponse();
        result.setBarcode(code);
        result.setFound(false);
        result.setFactChecked(false);
        result.setSource("OPEN_FOOD_FACTS");

        if (!StringUtils.hasText(code) || !BARCODE_PATTERN.matcher(code).matches()) {
            result.setMessage("Enter a valid barcode (6 to 32 characters).");
            return result;
        }

        try {
            String encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8);
            String url = "https://world.openfoodfacts.org/api/v2/product/" + encodedCode
                + ".json?fields=code,product_name,brands,categories,categories_tags,quantity,serving_size,serving_quantity,nutriments,image_front_small_url,image_front_url,image_url";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "calorie-tracker-barcode-lookup")
                .build();

            HttpResponse<String> response = sendWithHardTimeout(request);
            if (response.statusCode() >= 400) {
                return applyLocalFallback(code, result, "Barcode lookup failed right now. Try again.");
            }

            JsonNode root = objectMapper.readTree(response.body());
            int status = root.path("status").asInt(0);
            JsonNode product = root.path("product");

            if (status != 1 || product.isMissingNode() || product.isNull()) {
                return applyLocalFallback(code, result, "No verified match found for this barcode.");
            }

            String productName = product.path("product_name").asText("").trim();
            if (!StringUtils.hasText(productName)) {
                productName = "Scanned Food";
            }

            JsonNode nutriments = product.path("nutriments");
            Double calories = extractNutriment(nutriments, "energy-kcal_100g", "energy-kcal", "energy-kcal_value");
            Double protein = extractNutriment(nutriments, "proteins_100g", "proteins");
            Double carbs = extractNutriment(nutriments, "carbohydrates_100g", "carbohydrates");
            Double fats = extractNutriment(nutriments, "fat_100g", "fat");
            Double fiber = extractNutriment(nutriments, "fiber_100g", "fiber");
            Double sugar = extractNutriment(nutriments, "sugars_100g", "sugars");
            Double salt = extractNutriment(nutriments, "salt_100g", "salt");

            String servingSize = firstText(
                product.path("serving_size").asText(""),
                product.path("quantity").asText("")
            );
            Double servingQuantity = extractPositiveDouble(
                product.path("serving_quantity").asText(""),
                extractLeadingNumber(servingSize)
            );
            String servingUnit = extractServingUnit(servingSize);

            String categories = product.path("categories").asText("").trim();

            result.setFound(true);
            result.setBarcode(code);
            result.setName(productName);
            result.setBrand(product.path("brands").asText("").trim());
            result.setCategory(inferCategory(categories));
            result.setServingNote(StringUtils.hasText(servingSize) ? servingSize : "100 g");
            result.setServingQuantity(servingQuantity);
            result.setServingUnit(servingUnit);
            result.setCaloriesPer100g(calories);
            result.setProteinPer100g(protein);
            result.setCarbsPer100g(carbs);
            result.setFatsPer100g(fats);
            result.setFiberPer100g(fiber);
            result.setSugarPer100g(sugar);
            result.setSaltPer100g(salt);
            result.setImageUrl(
                foodMetadataService.sanitizeImageUrl(
                    firstText(
                        product.path("image_front_small_url").asText(""),
                        product.path("image_front_url").asText(""),
                        product.path("image_url").asText("")
                    )
                )
            );
            result.setSourceUrl("https://world.openfoodfacts.org/product/" + encodedCode);
            result.setFactChecked(calories != null && calories > 0);
            result.setMessage(result.getFactChecked()
                ? "Verified nutrition from Open Food Facts."
                : "Barcode found. Some nutrition fields are missing.");
            return result;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return applyLocalFallback(code, result, "Barcode service is temporarily unavailable.");
        }
    }

    private BarcodeLookupResponse applyLocalFallback(String code, BarcodeLookupResponse result, String fallbackMessage) {
        Ingredient localMatch = ingredientRepository
            .findByAliasesContainingIgnoreCaseOrderByNameAsc(code, PageRequest.of(0, 1))
            .stream()
            .findFirst()
            .orElse(null);

        if (localMatch == null) {
            result.setMessage(fallbackMessage);
            return result;
        }

        result.setFound(true);
        result.setName(localMatch.getName());
        result.setBrand("Local Dataset");
        result.setCategory(localMatch.getCategory() == null ? "OTHER" : localMatch.getCategory().name());
        result.setServingNote(StringUtils.hasText(localMatch.getServingNote()) ? localMatch.getServingNote() : "100 g");
        result.setServingQuantity(extractLeadingNumber(result.getServingNote()));
        result.setServingUnit(extractServingUnit(result.getServingNote()));
        result.setCaloriesPer100g(localMatch.getCaloriesPer100g());
        result.setProteinPer100g(localMatch.getProteinPer100g());
        result.setCarbsPer100g(localMatch.getCarbsPer100g());
        result.setFatsPer100g(localMatch.getFatsPer100g());
        result.setFiberPer100g(localMatch.getFiberPer100g());
        result.setImageUrl(
            foodMetadataService.resolveIngredientImageUrl(
                localMatch.getImageUrl(),
                localMatch.getName(),
                localMatch.getCategory(),
                localMatch.getCuisine()
            )
        );
        result.setFactChecked(Boolean.TRUE.equals(localMatch.getFactChecked()));
        result.setSource("LOCAL_DATASET");
        result.setSourceUrl(null);
        result.setMessage("Matched from local dataset fallback.");
        return result;
    }

    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            throw new IOException("Barcode request timed out.", timeoutException);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            }
            throw new IOException("Barcode request failed.", cause);
        }
    }

    private String sanitizeBarcode(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", "");
    }

    private String inferCategory(String categories) {
        String text = categories == null ? "" : categories.toLowerCase(Locale.ROOT);
        if (containsAny(text, "juice", "drink", "beverage", "coffee", "tea")) {
            return "BEVERAGE";
        }
        if (containsAny(text, "fish", "seafood")) {
            return "SEAFOOD";
        }
        if (containsAny(text, "meat", "chicken", "beef", "mutton", "lamb", "pork")) {
            return "MEAT";
        }
        if (containsAny(text, "milk", "yogurt", "curd", "dairy", "cheese", "ice cream")) {
            return "DAIRY";
        }
        if (containsAny(text, "rice")) {
            return "RICE";
        }
        if (containsAny(text, "fruit")) {
            return "FRUIT";
        }
        if (containsAny(text, "vegetable", "veg")) {
            return "VEGETABLE";
        }
        if (containsAny(text, "oil", "ghee", "butter")) {
            return "OIL";
        }
        if (containsAny(text, "snack", "chips", "cake", "biscuit")) {
            return "SNACK";
        }
        return "OTHER";
    }

    private boolean containsAny(String value, String... options) {
        for (String option : options) {
            if (value.contains(option)) {
                return true;
            }
        }
        return false;
    }

    private String extractServingUnit(String servingSize) {
        String normalized = (servingSize == null ? "" : servingSize).toLowerCase(Locale.ROOT);
        if (normalized.contains("kg") || normalized.contains("kilogram")) {
            return "kg";
        }
        if (normalized.contains("ml")) {
            return "ml";
        }
        if (normalized.contains(" l") || normalized.startsWith("l") || normalized.contains("litre") || normalized.contains("liter")) {
            return "l";
        }
        if (normalized.contains("serving")) {
            return "serving";
        }
        if (normalized.contains("count") || normalized.contains("piece")) {
            return "count";
        }
        return "g";
    }

    private Double extractNutriment(JsonNode nutriments, String... keys) {
        if (nutriments == null || nutriments.isMissingNode()) {
            return null;
        }

        for (String key : keys) {
            JsonNode node = nutriments.path(key);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            Double parsed = parseNumeric(node.asText(""));
            if (parsed != null && parsed >= 0 && parsed <= 1200) {
                return CalorieMath.round(parsed);
            }
        }

        return null;
    }

    private Double extractLeadingNumber(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace(',', '.');
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if ((current >= '0' && current <= '9') || current == '.') {
                digits.append(current);
                continue;
            }
            if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        return parseNumeric(digits.toString());
    }

    private Double parseNumeric(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double extractPositiveDouble(String preferredValue, Double fallback) {
        Double parsed = parseNumeric(preferredValue);
        if (parsed != null && parsed > 0) {
            return CalorieMath.round(parsed);
        }
        if (fallback != null && fallback > 0) {
            return CalorieMath.round(fallback);
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}

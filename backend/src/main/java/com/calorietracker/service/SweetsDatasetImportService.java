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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;
import com.calorietracker.repository.IngredientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SweetsDatasetImportService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(14);
    private static final int MAX_CONSECUTIVE_REQUEST_FAILURES = 4;
    private static final int MAX_QUERY_COUNT = 120;
    private static final int MAX_CORGIS_IMPORT = 1600;
    private static final String CORGIS_FOOD_DATASET_URL = "https://corgis-edu.github.io/corgis/datasets/json/food/food.json";

    private static final List<String> DEFAULT_COUNTRIES = List.of(
        "india",
        "china",
        "japan",
        "south-korea",
        "thailand",
        "vietnam",
        "indonesia",
        "philippines",
        "italy",
        "france",
        "germany",
        "spain",
        "greece",
        "turkey",
        "united-kingdom",
        "united-states",
        "mexico",
        "brazil",
        "argentina",
        "south-africa",
        "nigeria",
        "australia"
    );

    private static final List<String> COUNTRY_KEYWORDS = List.of(
        "dessert",
        "sweet",
        "sweets",
        "cake",
        "pastry",
        "ice cream",
        "chocolate",
        "pudding"
    );

    private static final Set<String> DESSERT_HINTS = Set.of(
        "dessert",
        "sweet",
        "sweets",
        "cake",
        "cakes",
        "pastry",
        "pastries",
        "cookie",
        "cookies",
        "biscuit",
        "brownie",
        "donut",
        "doughnut",
        "chocolate",
        "candy",
        "toffee",
        "fudge",
        "caramel",
        "pudding",
        "custard",
        "flan",
        "cheesecake",
        "tiramisu",
        "baklava",
        "kunafa",
        "knafeh",
        "halwa",
        "laddu",
        "ladoo",
        "kheer",
        "payasam",
        "jalebi",
        "rasgulla",
        "rasmalai",
        "barfi",
        "burfi",
        "gulab",
        "jamun",
        "imarti",
        "ghevar",
        "churro",
        "mochi",
        "dorayaki",
        "dango",
        "gelato",
        "kulfi",
        "icecream",
        "sundae",
        "waffle",
        "pancake",
        "pie",
        "macaron",
        "eclair",
        "profiterole"
    );

    private static final List<String> FAMOUS_SWEETS_QUERIES = List.of(
        "gulab jamun",
        "gulab jamoon",
        "jalebi",
        "rasgulla",
        "rasmalai",
        "kaju katli",
        "motichoor laddu",
        "besan laddu",
        "soan papdi",
        "mysore pak",
        "sandesh",
        "milk peda",
        "kalakand",
        "barfi",
        "coconut barfi",
        "gajar halwa",
        "moong dal halwa",
        "kheer",
        "payasam",
        "rabri",
        "basundi",
        "malpua",
        "imarti",
        "ghevar",
        "modak",
        "phirni",
        "baklava",
        "kunafa",
        "basbousa",
        "turkish delight",
        "tiramisu",
        "cheesecake",
        "brownie",
        "chocolate cake",
        "red velvet cake",
        "black forest cake",
        "donut",
        "doughnut",
        "apple pie",
        "blueberry pie",
        "macaron",
        "eclair",
        "profiterole",
        "panna cotta",
        "creme brulee",
        "cannoli",
        "mille feuille",
        "churros",
        "tres leches",
        "flan",
        "mochi",
        "dorayaki",
        "dango",
        "taiyaki",
        "mooncake",
        "bingsu",
        "gelato",
        "ice cream",
        "ice cream sundae",
        "frozen yogurt",
        "cupcake",
        "waffle",
        "pancake",
        "carrot cake",
        "alfajor",
        "brigadeiro",
        "pastel de nata",
        "halo halo",
        "ais kacang"
    );

    private static final List<CuratedSweetSeed> CURATED_FAMOUS_SWEETS = List.of(
        seed("Gulab Jamun", "Indian", 375, IngredientCategory.SNACK, "1 piece (40 g)", "gulab jamoon", "gulabjaman"),
        seed("Jalebi", "Indian", 459, IngredientCategory.SNACK, "1 piece (25 g)", "jilebi", "imarti jalebi"),
        seed("Rasgulla", "Indian", 186, IngredientCategory.DAIRY, "1 piece (50 g)", "rosogolla", "rasagulla"),
        seed("Rasmalai", "Indian", 236, IngredientCategory.DAIRY, "1 piece (60 g)", "ras malai"),
        seed("Kaju Katli", "Indian", 449, IngredientCategory.SNACK, "2 pieces (40 g)", "kaju barfi", "cashew katli"),
        seed("Motichoor Laddu", "Indian", 425, IngredientCategory.SNACK, "1 piece (35 g)", "motichur ladoo", "boondi laddu"),
        seed("Besan Laddu", "Indian", 514, IngredientCategory.SNACK, "1 piece (35 g)", "besan ladoo"),
        seed("Soan Papdi", "Indian", 520, IngredientCategory.SNACK, "1 piece (25 g)", "sohan papdi"),
        seed("Mysore Pak", "Indian", 510, IngredientCategory.SNACK, "1 piece (35 g)"),
        seed("Sandesh", "Indian", 315, IngredientCategory.DAIRY, "2 pieces (50 g)"),
        seed("Peda", "Indian", 340, IngredientCategory.DAIRY, "2 pieces (40 g)", "milk peda"),
        seed("Kalakand", "Indian", 389, IngredientCategory.DAIRY, "1 piece (40 g)"),
        seed("Barfi", "Indian", 430, IngredientCategory.SNACK, "1 piece (35 g)", "burfi"),
        seed("Gajar Halwa", "Indian", 315, IngredientCategory.SNACK, "1 bowl (100 g)", "carrot halwa"),
        seed("Moong Dal Halwa", "Indian", 460, IngredientCategory.SNACK, "1 bowl (100 g)"),
        seed("Kheer", "Indian", 180, IngredientCategory.DAIRY, "1 bowl (150 g)", "rice kheer"),
        seed("Payasam", "Indian", 185, IngredientCategory.DAIRY, "1 bowl (150 g)", "paayasam"),
        seed("Rabri", "Indian", 340, IngredientCategory.DAIRY, "1 bowl (100 g)", "rabdi"),
        seed("Basundi", "Indian", 280, IngredientCategory.DAIRY, "1 bowl (120 g)"),
        seed("Malpua", "Indian", 340, IngredientCategory.SNACK, "1 piece (60 g)"),
        seed("Imarti", "Indian", 420, IngredientCategory.SNACK, "1 piece (30 g)"),
        seed("Ghevar", "Indian", 515, IngredientCategory.SNACK, "1 piece (50 g)"),
        seed("Modak", "Indian", 395, IngredientCategory.SNACK, "1 piece (35 g)"),
        seed("Phirni", "Indian", 180, IngredientCategory.DAIRY, "1 bowl (150 g)", "firni"),
        seed("Baklava", "Mediterranean", 428, IngredientCategory.SNACK, "1 piece (40 g)"),
        seed("Kunafa", "Middle Eastern", 390, IngredientCategory.SNACK, "1 piece (80 g)", "knafeh", "kanafeh"),
        seed("Basbousa", "Middle Eastern", 360, IngredientCategory.SNACK, "1 piece (60 g)", "revani"),
        seed("Turkish Delight", "Middle Eastern", 330, IngredientCategory.SNACK, "3 pieces (45 g)", "lokum"),
        seed("Tiramisu", "Italian", 283, IngredientCategory.SNACK, "1 serving (100 g)"),
        seed("Cheesecake", "Western", 321, IngredientCategory.SNACK, "1 slice (100 g)"),
        seed("Brownie", "Western", 466, IngredientCategory.SNACK, "1 piece (60 g)"),
        seed("Chocolate Cake", "Western", 371, IngredientCategory.SNACK, "1 slice (100 g)"),
        seed("Red Velvet Cake", "Western", 360, IngredientCategory.SNACK, "1 slice (100 g)"),
        seed("Black Forest Cake", "European", 280, IngredientCategory.SNACK, "1 slice (100 g)"),
        seed("Apple Pie", "Western", 237, IngredientCategory.SNACK, "1 slice (120 g)"),
        seed("Blueberry Pie", "Western", 265, IngredientCategory.SNACK, "1 slice (120 g)"),
        seed("Donut", "Western", 452, IngredientCategory.SNACK, "1 donut (60 g)", "doughnut"),
        seed("Macaron", "French", 440, IngredientCategory.SNACK, "3 pieces (45 g)"),
        seed("Eclair", "French", 262, IngredientCategory.SNACK, "1 piece (80 g)"),
        seed("Profiterole", "French", 334, IngredientCategory.SNACK, "3 pieces (90 g)"),
        seed("Panna Cotta", "Italian", 223, IngredientCategory.DAIRY, "1 serving (100 g)"),
        seed("Creme Brulee", "French", 331, IngredientCategory.DAIRY, "1 serving (100 g)", "cr me br l e"),
        seed("Cannoli", "Italian", 343, IngredientCategory.SNACK, "1 piece (70 g)"),
        seed("Mille Feuille", "French", 420, IngredientCategory.SNACK, "1 piece (90 g)", "napoleon pastry"),
        seed("Churros", "Spanish", 447, IngredientCategory.SNACK, "3 pieces (90 g)"),
        seed("Tres Leches Cake", "Latin American", 310, IngredientCategory.SNACK, "1 slice (110 g)"),
        seed("Flan", "Latin American", 150, IngredientCategory.DAIRY, "1 serving (100 g)", "creme caramel"),
        seed("Mochi", "Japanese", 223, IngredientCategory.SNACK, "2 pieces (60 g)"),
        seed("Dorayaki", "Japanese", 284, IngredientCategory.SNACK, "1 piece (65 g)"),
        seed("Dango", "Japanese", 180, IngredientCategory.SNACK, "3 pieces (90 g)"),
        seed("Taiyaki", "Japanese", 270, IngredientCategory.SNACK, "1 piece (100 g)"),
        seed("Mooncake", "Chinese", 420, IngredientCategory.SNACK, "1 slice (60 g)"),
        seed("Bingsu", "Korean", 127, IngredientCategory.SNACK, "1 bowl (150 g)"),
        seed("Gelato", "Italian", 180, IngredientCategory.DAIRY, "1 scoop (70 g)"),
        seed("Ice Cream Sundae", "Global", 220, IngredientCategory.DAIRY, "1 cup (140 g)"),
        seed("Frozen Yogurt", "Global", 159, IngredientCategory.DAIRY, "1 cup (120 g)"),
        seed("Cupcake", "Global", 305, IngredientCategory.SNACK, "1 cupcake (70 g)"),
        seed("Waffle", "Western", 291, IngredientCategory.SNACK, "1 waffle (75 g)"),
        seed("Pancake", "Western", 227, IngredientCategory.SNACK, "1 pancake (60 g)"),
        seed("Carrot Cake", "Western", 415, IngredientCategory.SNACK, "1 slice (100 g)"),
        seed("Alfajor", "Latin American", 470, IngredientCategory.SNACK, "1 piece (50 g)"),
        seed("Brigadeiro", "Brazilian", 430, IngredientCategory.SNACK, "2 pieces (40 g)"),
        seed("Pastel De Nata", "Portuguese", 298, IngredientCategory.SNACK, "1 tart (60 g)"),
        seed("Halo Halo", "Filipino", 150, IngredientCategory.DAIRY, "1 serving (180 g)"),
        seed("Ais Kacang", "Southeast Asian", 140, IngredientCategory.SNACK, "1 serving (180 g)"),
        seed("Kulfi", "Indian", 260, IngredientCategory.DAIRY, "1 stick (80 g)"),
        seed("Mango Kulfi", "Indian", 200, IngredientCategory.DAIRY, "1 stick (80 g)"),
        seed("Falooda", "Indian", 210, IngredientCategory.DAIRY, "1 glass (200 ml)"),
        seed("Shahi Tukda", "Indian", 390, IngredientCategory.SNACK, "1 piece (90 g)")
    );

    private final IngredientRepository ingredientRepository;
    private final FoodMetadataService foodMetadataService;
    private final TheMealDbImportService theMealDbImportService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SweetsDatasetImportService(
        IngredientRepository ingredientRepository,
        FoodMetadataService foodMetadataService,
        TheMealDbImportService theMealDbImportService,
        ObjectMapper objectMapper
    ) {
        this.ingredientRepository = ingredientRepository;
        this.foodMetadataService = foodMetadataService;
        this.theMealDbImportService = theMealDbImportService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public Map<String, Object> importSweetsAndDesserts(
        List<String> countries,
        int pages,
        int pageSize,
        int maxPerQuery,
        int maxMealDbDesserts,
        boolean includeCuratedFallback
    ) {
        List<String> normalizedCountries = normalizeCountries(countries);
        int safePages = clampInt(pages, 1, 3);
        int safePageSize = clampInt(pageSize, 25, 260);
        int safeMaxPerQuery = clampInt(maxPerQuery, 2, 40);
        int safeMealDbDesserts = clampInt(maxMealDbDesserts, 5, 500);

        List<String> queries = buildQuerySet(normalizedCountries);
        OpenFoodFactsDessertImportStats openFoodFactsStats = importDessertsFromOpenFoodFacts(
            queries,
            safePages,
            safePageSize,
            safeMaxPerQuery
        );
        int corgisImported = importDessertsFromCorgisDataset(MAX_CORGIS_IMPORT);

        Map<String, Integer> mealDbDessertStats = theMealDbImportService.importDessertCategory(safeMealDbDesserts);
        int curatedFallbackAdded = includeCuratedFallback ? importCuratedFallbackDesserts() : 0;

        int dessertDishesImported = mealDbDessertStats.getOrDefault("TOTAL_IMPORTED", 0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ingredientsFromOpenFoodFacts", openFoodFactsStats.imported());
        summary.put("ingredientsUpdatedFromOpenFoodFacts", openFoodFactsStats.updated());
        summary.put("ingredientsFromUsdaCorgis", corgisImported);
        summary.put("openFoodFactsQueriesProcessed", openFoodFactsStats.queriesProcessed());
        summary.put("openFoodFactsRequestFailures", openFoodFactsStats.requestFailures());
        summary.put("openFoodFactsQueriesTotal", queries.size());
        summary.put("dishesFromMealDbDessertCategory", dessertDishesImported);
        summary.put("curatedFallbackDessertsAdded", curatedFallbackAdded);
        summary.put(
            "totalDessertSignals",
            openFoodFactsStats.imported() + corgisImported + dessertDishesImported + curatedFallbackAdded
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("source", "OpenFoodFacts + TheMealDB + Curated Fallback");
        response.put("countries", normalizedCountries);
        response.put("summary", summary);
        response.put("mealDbDesserts", mealDbDessertStats);
        return response;
    }

    private int importDessertsFromCorgisDataset(int maxRecords) {
        int safeMaxRecords = clampInt(maxRecords, 80, 3000);
        Set<String> existingNames = ingredientRepository.findAll().stream()
            .map(Ingredient::getName)
            .map(this::normalizeName)
            .collect(Collectors.toCollection(HashSet::new));
        List<Ingredient> pendingSaves = new ArrayList<>();

        int imported = 0;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(CORGIS_FOOD_DATASET_URL))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "calorie-tracker-usda-dessert-import")
                .build();
            HttpResponse<String> response = sendWithHardTimeout(request);
            if (response.statusCode() >= 400) {
                return 0;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                return 0;
            }

            for (JsonNode item : root) {
                if (imported >= safeMaxRecords) {
                    break;
                }

                String category = item.path("Category").asText("").trim();
                String description = sanitizeName(item.path("Description").asText(""));
                if (!StringUtils.hasText(description)) {
                    continue;
                }

                if (!looksLikeCorgisDessert(category, description)) {
                    continue;
                }

                String normalizedName = normalizeName(description);
                if (!existingNames.add(normalizedName)) {
                    continue;
                }

                JsonNode data = item.path("Data");
                Double carbs = parseNonNegative(data.path("Carbohydrate"));
                Double protein = parseNonNegative(data.path("Protein"));
                Double fats = parseNonNegative(data.path("Fat").path("Total Lipid"));
                Double fiber = parseNonNegative(data.path("Fiber"));

                double calories = estimateCaloriesFromMacros(protein, carbs, fats);
                if (calories <= 0 || calories > 900) {
                    continue;
                }

                Ingredient ingredient = new Ingredient(
                    description,
                    guessDessertCategory(category, description),
                    inferCuisineFromQuery(category + " " + description),
                    CalorieMath.round(calories),
                    "100 g",
                    true,
                    "USDA_CORGIS_DESSERTS"
                );
                ingredient.setProteinPer100g(protein);
                ingredient.setCarbsPer100g(carbs);
                ingredient.setFatsPer100g(fats);
                ingredient.setFiberPer100g(fiber);
                ingredient.setRegionalAvailability(foodMetadataService.toCsv(List.of("Global", "United States")));
                foodMetadataService.applyDefaults(ingredient);

                pendingSaves.add(ingredient);
                if (pendingSaves.size() >= 180) {
                    imported += flushPending(pendingSaves, existingNames);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        imported += flushPending(pendingSaves, existingNames);
        return imported;
    }

    private OpenFoodFactsDessertImportStats importDessertsFromOpenFoodFacts(
        List<String> searchQueries,
        int pageCount,
        int pageSize,
        int maxPerQuery
    ) {
        Map<String, Ingredient> existingByNormalizedName = ingredientRepository.findAll().stream()
            .collect(
                Collectors.toMap(
                    ingredient -> normalizeName(ingredient.getName()),
                    ingredient -> ingredient,
                    (left, right) -> left,
                    LinkedHashMap::new
                )
            );
        Set<String> existingNames = new HashSet<>(existingByNormalizedName.keySet());
        List<Ingredient> pendingSaves = new ArrayList<>();

        int imported = 0;
        int updated = 0;
        int requestFailures = 0;
        int consecutiveFailures = 0;
        int processedQueries = 0;

        for (String query : searchQueries) {
            if (!StringUtils.hasText(query)) {
                continue;
            }
            processedQueries++;
            int importedForQuery = 0;

            for (int page = 1; page <= pageCount; page++) {
                if (importedForQuery >= maxPerQuery) {
                    break;
                }

                String url = buildOpenFoodFactsUrl(query, page, pageSize);
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "calorie-tracker-dessert-import")
                        .build();

                    HttpResponse<String> response = sendWithHardTimeout(request);
                    if (response.statusCode() >= 400) {
                        requestFailures++;
                        consecutiveFailures++;
                        if (consecutiveFailures >= MAX_CONSECUTIVE_REQUEST_FAILURES) {
                            imported += flushPending(pendingSaves, existingNames);
                            return new OpenFoodFactsDessertImportStats(imported, updated, processedQueries, requestFailures);
                        }
                        break;
                    }
                    consecutiveFailures = 0;

                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode products = root.path("products");
                    if (!products.isArray() || products.isEmpty()) {
                        break;
                    }

                    for (JsonNode product : products) {
                        if (importedForQuery >= maxPerQuery) {
                            break;
                        }

                        String name = sanitizeName(product.path("product_name").asText(""));
                        if (!StringUtils.hasText(name)) {
                            continue;
                        }

                        JsonNode nutriments = product.path("nutriments");
                        double calories = extractCalories(nutriments);
                        if (calories <= 0 || calories > 900) {
                            continue;
                        }

                        String categories = product.path("categories").asText("");
                        String tags = tagsToText(product.path("categories_tags"));
                        if (!looksLikeDessert(name, categories, tags, query)) {
                            continue;
                        }

                        String normalizedName = normalizeName(name);
                        Ingredient existingIngredient = existingByNormalizedName.get(normalizedName);
                        if (existingIngredient != null) {
                            if (upgradeExistingIngredientFromRemote(existingIngredient, nutriments, query, product)) {
                                ingredientRepository.save(existingIngredient);
                                updated++;
                            }
                            continue;
                        }
                        existingNames.add(normalizedName);

                        Ingredient ingredient = new Ingredient(
                            name,
                            guessDessertCategory(name, categories + " " + tags),
                            inferCuisineFromQuery(query),
                            CalorieMath.round(calories),
                            "100 g",
                            true,
                            "OPEN_FOOD_FACTS_DESSERT"
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
                            foodMetadataService.toCsv(
                                mergeDistinct(
                                    List.of("Global"),
                                    foodMetadataService.buildRegionalAvailability(ingredient.getCuisine())
                                )
                            )
                        );
                        foodMetadataService.applyDefaults(ingredient);
                        pendingSaves.add(ingredient);
                        importedForQuery++;

                        if (pendingSaves.size() >= 120) {
                            imported += flushPending(pendingSaves, existingNames);
                        }
                    }
                } catch (IOException ignored) {
                    requestFailures++;
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_REQUEST_FAILURES) {
                        imported += flushPending(pendingSaves, existingNames);
                        return new OpenFoodFactsDessertImportStats(imported, updated, processedQueries, requestFailures);
                    }
                    break;
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    imported += flushPending(pendingSaves, existingNames);
                    return new OpenFoodFactsDessertImportStats(imported, updated, processedQueries, requestFailures);
                }
            }
        }

        imported += flushPending(pendingSaves, existingNames);
        return new OpenFoodFactsDessertImportStats(imported, updated, processedQueries, requestFailures);
    }

    private boolean upgradeExistingIngredientFromRemote(
        Ingredient ingredient,
        JsonNode nutriments,
        String query,
        JsonNode product
    ) {
        boolean changed = false;
        double remoteCalories = extractCalories(nutriments);
        Double remoteProtein = extractNutriment(nutriments, "proteins_100g", "proteins");
        Double remoteCarbs = extractNutriment(nutriments, "carbohydrates_100g", "carbohydrates");
        Double remoteFats = extractNutriment(nutriments, "fat_100g", "fat");
        Double remoteFiber = extractNutriment(nutriments, "fiber_100g", "fiber");

        if (remoteCalories > 0 && remoteCalories <= 900) {
            double roundedCalories = CalorieMath.round(remoteCalories);
            if (ingredient.getCaloriesPer100g() == null || Math.abs(ingredient.getCaloriesPer100g() - roundedCalories) > 0.1) {
                ingredient.setCaloriesPer100g(roundedCalories);
                changed = true;
            }
        }

        if (remoteProtein != null && !remoteProtein.equals(ingredient.getProteinPer100g())) {
            ingredient.setProteinPer100g(remoteProtein);
            changed = true;
        }
        if (remoteCarbs != null && !remoteCarbs.equals(ingredient.getCarbsPer100g())) {
            ingredient.setCarbsPer100g(remoteCarbs);
            changed = true;
        }
        if (remoteFats != null && !remoteFats.equals(ingredient.getFatsPer100g())) {
            ingredient.setFatsPer100g(remoteFats);
            changed = true;
        }
        if (remoteFiber != null && !remoteFiber.equals(ingredient.getFiberPer100g())) {
            ingredient.setFiberPer100g(remoteFiber);
            changed = true;
        }

        String inferredCuisine = inferCuisineFromQuery(query);
        if (!StringUtils.hasText(ingredient.getCuisine()) || "Global".equalsIgnoreCase(ingredient.getCuisine())) {
            ingredient.setCuisine(inferredCuisine);
            changed = true;
        }

        String resolvedImage = foodMetadataService.sanitizeImageUrl(
            firstText(
                product.path("image_front_small_url").asText(""),
                product.path("image_front_url").asText(""),
                product.path("image_url").asText("")
            )
        );
        if (StringUtils.hasText(resolvedImage) && !resolvedImage.equals(ingredient.getImageUrl())) {
            ingredient.setImageUrl(resolvedImage);
            changed = true;
        }

        if (Boolean.FALSE.equals(ingredient.getFactChecked())) {
            ingredient.setFactChecked(true);
            changed = true;
        }
        if (!"OPEN_FOOD_FACTS_DESSERT".equalsIgnoreCase(ingredient.getSource())) {
            ingredient.setSource("OPEN_FOOD_FACTS_DESSERT");
            changed = true;
        }

        if (changed) {
            foodMetadataService.applyDefaults(ingredient);
        }
        return changed;
    }

    private int flushPending(List<Ingredient> pendingSaves, Set<String> existingNames) {
        if (pendingSaves.isEmpty()) {
            return 0;
        }

        int inserted = 0;
        for (Ingredient ingredient : pendingSaves) {
            try {
                ingredientRepository.save(ingredient);
                inserted++;
            } catch (DataIntegrityViolationException ignored) {
                existingNames.remove(normalizeName(ingredient.getName()));
            }
        }

        pendingSaves.clear();
        return inserted;
    }

    private int importCuratedFallbackDesserts() {
        Map<String, Ingredient> existingByNormalizedName = ingredientRepository.findAll().stream()
            .collect(
                Collectors.toMap(
                    ingredient -> normalizeName(ingredient.getName()),
                    ingredient -> ingredient,
                    (left, right) -> left,
                    LinkedHashMap::new
                )
            );
        Set<String> existingNames = new HashSet<>(existingByNormalizedName.keySet());

        int imported = 0;
        for (CuratedSweetSeed seed : CURATED_FAMOUS_SWEETS) {
            String normalizedName = normalizeName(seed.name());
            Ingredient existingIngredient = existingByNormalizedName.get(normalizedName);
            if (existingIngredient != null) {
                boolean changed = false;
                if (Boolean.FALSE.equals(existingIngredient.getFactChecked())) {
                    existingIngredient.setFactChecked(true);
                    changed = true;
                }

                if (shouldUpgradeDessertSource(existingIngredient.getSource())) {
                    if ("OPEN_FOOD_FACTS".equalsIgnoreCase(existingIngredient.getSource())) {
                        existingIngredient.setSource("OPEN_FOOD_FACTS_DESSERT");
                    } else {
                        existingIngredient.setSource("CURATED_DESSERT_REFERENCE");
                    }
                    changed = true;
                }

                if (existingIngredient.getCategory() == null || existingIngredient.getCategory() == IngredientCategory.OTHER) {
                    existingIngredient.setCategory(seed.category());
                    changed = true;
                }
                if (existingIngredient.getCaloriesPer100g() == null || existingIngredient.getCaloriesPer100g() <= 0) {
                    existingIngredient.setCaloriesPer100g(CalorieMath.round(seed.caloriesPer100g()));
                    changed = true;
                }
                if (!StringUtils.hasText(existingIngredient.getServingNote())) {
                    existingIngredient.setServingNote(seed.servingNote());
                    changed = true;
                }
                if (!seed.aliases().isEmpty() && !StringUtils.hasText(existingIngredient.getAliases())) {
                    existingIngredient.setAliases(foodMetadataService.toCsv(seed.aliases()));
                    changed = true;
                }

                if (changed) {
                    foodMetadataService.applyDefaults(existingIngredient);
                    ingredientRepository.save(existingIngredient);
                }
                continue;
            }
            existingNames.add(normalizedName);

            Ingredient ingredient = new Ingredient(
                seed.name(),
                seed.category(),
                seed.cuisine(),
                CalorieMath.round(seed.caloriesPer100g()),
                seed.servingNote(),
                true,
                "CURATED_DESSERT_REFERENCE"
            );

            if (!seed.aliases().isEmpty()) {
                ingredient.setAliases(foodMetadataService.toCsv(seed.aliases()));
            }
            ingredient.setRegionalAvailability(foodMetadataService.toCsv(foodMetadataService.buildRegionalAvailability(seed.cuisine())));
            foodMetadataService.applyDefaults(ingredient);

            try {
                ingredientRepository.save(ingredient);
                imported++;
            } catch (DataIntegrityViolationException ignored) {
                existingNames.remove(normalizedName);
            }
        }

        return imported;
    }

    private List<String> buildQuerySet(List<String> countries) {
        Set<String> querySet = new LinkedHashSet<>();

        for (String country : countries) {
            String countryLabel = country.replace('-', ' ');
            for (String keyword : COUNTRY_KEYWORDS) {
                querySet.add(countryLabel + " " + keyword);
            }
        }

        querySet.addAll(FAMOUS_SWEETS_QUERIES);
        return querySet.stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .limit(MAX_QUERY_COUNT)
            .collect(Collectors.toList());
    }

    private List<String> normalizeCountries(List<String> countries) {
        List<String> values = (countries == null || countries.isEmpty()) ? DEFAULT_COUNTRIES : countries;
        return values.stream()
            .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
            .map(value -> value.replaceAll("\\s+", "-"))
            .filter(StringUtils::hasText)
            .distinct()
            .limit(40)
            .collect(Collectors.toList());
    }

    private String buildOpenFoodFactsUrl(String query, int page, int pageSize) {
        String encodedSearch = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return String.format(
            Locale.ROOT,
            "https://world.openfoodfacts.org/cgi/search.pl?action=process&json=1&page=%d&page_size=%d&search_terms=%s&fields=product_name,nutriments,categories,categories_tags,image_url,image_front_url,image_front_small_url",
            Math.max(1, page),
            Math.max(20, pageSize),
            encodedSearch
        );
    }

    private String tagsToText(JsonNode categoriesTags) {
        if (categoriesTags == null || !categoriesTags.isArray()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        categoriesTags.forEach(node -> tags.add(node.asText("")));
        return String.join(" ", tags);
    }

    private boolean looksLikeDessert(String name, String categories, String tags, String query) {
        String merged = (name + " " + categories + " " + tags + " " + query).toLowerCase(Locale.ROOT);
        for (String hint : DESSERT_HINTS) {
            if (merged.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCorgisDessert(String category, String description) {
        String merged = (category + " " + description).toLowerCase(Locale.ROOT);
        if (containsAny(merged, "sweet potato", "sweet and sour", "coffee", "tea", "infant formula")) {
            return false;
        }
        return containsAny(
            merged,
            "dessert",
            "cake",
            "cupcake",
            "cookie",
            "pie",
            "brownie",
            "muffin",
            "pastry",
            "doughnut",
            "donut",
            "pudding",
            "custard",
            "gelatin",
            "ice cream",
            "frozen yogurt",
            "sundae",
            "candy",
            "chocolate",
            "fudge",
            "tart",
            "cheesecake",
            "cream puff",
            "milk shake"
        );
    }

    private IngredientCategory guessDessertCategory(String name, String metadata) {
        String merged = (name + " " + metadata).toLowerCase(Locale.ROOT);
        if (containsAny(merged, "ice cream", "gelato", "kulfi", "milk", "cream", "cheese", "yogurt", "custard", "flan")) {
            return IngredientCategory.DAIRY;
        }
        if (containsAny(merged, "drink", "beverage", "shake", "smoothie")) {
            return IngredientCategory.BEVERAGE;
        }
        if (containsAny(merged, "syrup", "caramel", "chocolate sauce")) {
            return IngredientCategory.SAUCE;
        }
        return IngredientCategory.SNACK;
    }

    private String inferCuisineFromQuery(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "india", "indian", "desi")) {
            return "Indian";
        }
        if (containsAny(normalized, "china", "chinese")) {
            return "Chinese";
        }
        if (containsAny(normalized, "japan", "japanese")) {
            return "Japanese";
        }
        if (containsAny(normalized, "korea", "korean")) {
            return "Korean";
        }
        if (containsAny(normalized, "thai", "thailand")) {
            return "Thai";
        }
        if (containsAny(normalized, "vietnam", "vietnamese")) {
            return "Vietnamese";
        }
        if (containsAny(normalized, "italy", "italian")) {
            return "Italian";
        }
        if (containsAny(normalized, "france", "french")) {
            return "French";
        }
        if (containsAny(normalized, "spain", "spanish")) {
            return "Spanish";
        }
        if (containsAny(normalized, "turkey", "turkish")) {
            return "Turkish";
        }
        if (containsAny(normalized, "mexico", "mexican")) {
            return "Mexican";
        }
        if (containsAny(normalized, "brazil", "brazilian")) {
            return "Brazilian";
        }
        if (containsAny(normalized, "argentina")) {
            return "Argentinian";
        }
        if (containsAny(normalized, "united states", "american", "usa")) {
            return "American";
        }
        if (containsAny(normalized, "united kingdom", "british")) {
            return "British";
        }
        if (containsAny(normalized, "middle east", "arab", "saudi", "uae")) {
            return "Middle Eastern";
        }

        return "Global Dessert";
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

    private String sanitizeName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() < 2) {
            return "";
        }
        if (cleaned.length() > 170) {
            cleaned = cleaned.substring(0, 170);
        }
        return cleaned;
    }

    private Double parseNonNegative(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            double value = node.asDouble();
            if (value >= 0) {
                return CalorieMath.round(value);
            }
            return null;
        }
        if (node.isTextual()) {
            try {
                double value = Double.parseDouble(node.asText());
                if (value >= 0) {
                    return CalorieMath.round(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private double estimateCaloriesFromMacros(Double protein, Double carbs, Double fats) {
        double p = protein == null ? 0.0 : protein;
        double c = carbs == null ? 0.0 : carbs;
        double f = fats == null ? 0.0 : fats;
        double calories = (p * 4.0) + (c * 4.0) + (f * 9.0);
        return CalorieMath.round(calories);
    }

    private String normalizeName(String value) {
        return foodMetadataService.normalizeToken(value);
    }

    private HttpResponse<String> sendWithHardTimeout(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IOException("Dessert import request timed out.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            if (cause instanceof InterruptedException interruptedEx) {
                Thread.currentThread().interrupt();
                throw interruptedEx;
            }
            throw new IOException("Dessert import request failed.", cause);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private List<String> mergeDistinct(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first.stream().filter(StringUtils::hasText).toList());
        }
        if (second != null) {
            merged.addAll(second.stream().filter(StringUtils::hasText).toList());
        }
        return new ArrayList<>(merged);
    }

    private boolean containsAny(String value, String... options) {
        for (String option : options) {
            if (value.contains(option)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldUpgradeDessertSource(String source) {
        if (!StringUtils.hasText(source)) {
            return true;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("OPEN_FOOD_FACTS")
            || normalized.equals("CURATED")
            || normalized.equals("CURATED_DESSERT_FALLBACK")
            || normalized.equals("OPEN_FOOD_FACTS_DESSERT");
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static CuratedSweetSeed seed(
        String name,
        String cuisine,
        double caloriesPer100g,
        IngredientCategory category,
        String servingNote,
        String... aliases
    ) {
        return new CuratedSweetSeed(
            name,
            cuisine,
            caloriesPer100g,
            category,
            servingNote,
            Arrays.stream(aliases)
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .distinct()
                .collect(Collectors.toList())
        );
    }

    private record OpenFoodFactsDessertImportStats(int imported, int updated, int queriesProcessed, int requestFailures) {
    }

    private record CuratedSweetSeed(
        String name,
        String cuisine,
        double caloriesPer100g,
        IngredientCategory category,
        String servingNote,
        List<String> aliases
    ) {
    }
}

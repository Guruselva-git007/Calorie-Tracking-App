package com.calorietracker.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.Ingredient;
import com.calorietracker.entity.IngredientCategory;

@Service
public class FoodMetadataService {

    private record MacroShare(double proteinCalShare, double carbCalShare, double fatCalShare, double fiberFactor) {
    }

    private final Map<IngredientCategory, MacroShare> macroShares = new HashMap<>();
    private final Map<IngredientCategory, Double> baseCategoryPriceUsd = new HashMap<>();
    private final Map<String, Double> namePriceOverrides = new HashMap<>();
    private final Map<String, List<String>> aliasGroups = new LinkedHashMap<>();
    private final Map<String, String> aliasToCanonical = new HashMap<>();
    private final Map<String, Double> currencyRates = new LinkedHashMap<>();
    private static final int MAX_IMAGE_URL_LENGTH = 900;

    public FoodMetadataService() {
        initializeMacroProfiles();
        initializePriceProfiles();
        initializeAliasVocabulary();
        initializeCurrencyRates();
    }

    public void applyDefaults(Ingredient ingredient) {
        if (ingredient == null) {
            return;
        }

        NutritionProfile profile = inferNutrition(
            ingredient.getCategory(),
            ingredient.getCaloriesPer100g(),
            ingredient.getProteinPer100g(),
            ingredient.getCarbsPer100g(),
            ingredient.getFatsPer100g(),
            ingredient.getFiberPer100g()
        );

        ingredient.setProteinPer100g(profile.proteinPer100g());
        ingredient.setCarbsPer100g(profile.carbsPer100g());
        ingredient.setFatsPer100g(profile.fatsPer100g());
        ingredient.setFiberPer100g(profile.fiberPer100g());

        if (ingredient.getAveragePriceUsd() == null || ingredient.getAveragePriceUsd() <= 0) {
            ingredient.setAveragePriceUsd(estimateAveragePriceUsd(ingredient.getName(), ingredient.getCategory()));
        }

        if (!StringUtils.hasText(ingredient.getAveragePriceUnit())) {
            ingredient.setAveragePriceUnit(defaultPriceUnit(ingredient.getCategory()));
        }

        if (!StringUtils.hasText(ingredient.getAliases())) {
            ingredient.setAliases(toCsv(buildAliases(ingredient.getName())));
        }

        if (!StringUtils.hasText(ingredient.getRegionalAvailability())) {
            ingredient.setRegionalAvailability(toCsv(buildRegionalAvailability(ingredient.getCuisine())));
        }

        ingredient.setImageUrl(
            resolveIngredientImageUrl(
                ingredient.getImageUrl(),
                ingredient.getName(),
                ingredient.getCategory(),
                ingredient.getCuisine()
            )
        );
    }

    public NutritionProfile inferNutrition(
        IngredientCategory category,
        Double caloriesPer100g,
        Double proteinPer100g,
        Double carbsPer100g,
        Double fatsPer100g,
        Double fiberPer100g
    ) {
        MacroShare share = macroShares.getOrDefault(category, macroShares.get(IngredientCategory.OTHER));
        double calories = clamp(caloriesPer100g == null ? 120.0 : caloriesPer100g, 1.0, 900.0);

        double protein = proteinPer100g == null ? calories * share.proteinCalShare() / 4.0 : proteinPer100g;
        double carbs = carbsPer100g == null ? calories * share.carbCalShare() / 4.0 : carbsPer100g;
        double fats = fatsPer100g == null ? calories * share.fatCalShare() / 9.0 : fatsPer100g;
        double fiber = fiberPer100g == null ? Math.max(0.0, carbs * share.fiberFactor()) : fiberPer100g;

        if (category == IngredientCategory.OIL) {
            protein = 0.0;
            carbs = 0.0;
            fiber = 0.0;
        }

        return new NutritionProfile(
            CalorieMath.round(clamp(protein, 0.0, 100.0)),
            CalorieMath.round(clamp(carbs, 0.0, 100.0)),
            CalorieMath.round(clamp(fats, 0.0, 100.0)),
            CalorieMath.round(clamp(fiber, 0.0, 60.0))
        );
    }

    public double estimateAveragePriceUsd(String ingredientName, IngredientCategory category) {
        String normalizedName = normalizeToken(ingredientName);
        for (Map.Entry<String, Double> override : namePriceOverrides.entrySet()) {
            if (normalizedName.contains(normalizeToken(override.getKey()))) {
                return CalorieMath.round(override.getValue());
            }
        }

        return CalorieMath.round(baseCategoryPriceUsd.getOrDefault(category, 5.0));
    }

    public String defaultPriceUnit(IngredientCategory category) {
        if (category == IngredientCategory.JUICE
            || category == IngredientCategory.BEVERAGE
            || category == IngredientCategory.OIL
            || category == IngredientCategory.SAUCE) {
            return "l";
        }
        return "kg";
    }

    public Set<String> expandSearchTerms(String search) {
        if (!StringUtils.hasText(search)) {
            return Collections.emptySet();
        }

        Set<String> terms = new LinkedHashSet<>();
        List<String> rawTokens = Arrays.stream(search.trim().split("\\s+"))
            .filter(token -> token.length() >= 2)
            .collect(Collectors.toList());

        terms.add(search.trim());
        terms.add(search.trim().replace("-", " "));
        terms.add(search.trim().replace(" ", ""));

        for (String token : rawTokens) {
            terms.add(token);

            String normalizedToken = normalizeToken(token);
            String canonical = aliasToCanonical.get(normalizedToken);
            if (canonical != null) {
                terms.add(canonical);
                terms.addAll(aliasGroups.getOrDefault(canonical, List.of()));
            }
        }

        String normalizedPhrase = normalizeToken(search);
        String phraseCanonical = aliasToCanonical.get(normalizedPhrase);
        if (phraseCanonical != null) {
            terms.add(phraseCanonical);
            terms.addAll(aliasGroups.getOrDefault(phraseCanonical, List.of()));
        }

        return terms.stream()
            .map(String::trim)
            .filter(term -> term.length() >= 2)
            .limit(14)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public List<String> buildAliases(String ingredientName) {
        if (!StringUtils.hasText(ingredientName)) {
            return List.of();
        }

        String normalizedName = normalizeToken(ingredientName);
        String canonical = aliasToCanonical.get(normalizedName);
        Set<String> aliases = new LinkedHashSet<>();

        if (canonical != null) {
            aliases.addAll(aliasGroups.getOrDefault(canonical, List.of()));
        }

        String naturalName = ingredientName.trim();
        aliases.removeIf(alias -> normalizeToken(alias).equals(normalizedName));

        if (naturalName.contains("-")) {
            aliases.add(naturalName.replace("-", " "));
        }
        if (naturalName.contains(" ")) {
            aliases.add(naturalName.replace(" ", ""));
        }

        return aliases.stream().limit(15).collect(Collectors.toList());
    }

    public List<String> buildRegionalAvailability(String cuisine) {
        Set<String> regions = new LinkedHashSet<>();
        regions.add("Global");

        if (!StringUtils.hasText(cuisine)) {
            return new ArrayList<>(regions);
        }

        String normalized = cuisine.trim().toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "indian", "south asian")) {
            regions.addAll(List.of("India", "Pakistan", "Bangladesh", "Nepal", "Sri Lanka"));
        } else if (containsAny(normalized, "chinese", "east asian")) {
            regions.addAll(List.of("China", "Taiwan", "Singapore", "Malaysia", "Hong Kong"));
        } else if (containsAny(normalized, "japanese")) {
            regions.addAll(List.of("Japan", "South Korea"));
        } else if (containsAny(normalized, "mediterranean", "greek", "italian", "spanish")) {
            regions.addAll(List.of("Italy", "Greece", "Spain", "Turkey", "Morocco"));
        } else if (containsAny(normalized, "african")) {
            regions.addAll(List.of("Nigeria", "Kenya", "Morocco", "South Africa"));
        } else if (containsAny(normalized, "middle eastern")) {
            regions.addAll(List.of("UAE", "Saudi Arabia", "Egypt", "Jordan", "Turkey"));
        } else if (containsAny(normalized, "mexican", "latin", "south american")) {
            regions.addAll(List.of("Mexico", "Brazil", "Argentina", "Peru", "Chile"));
        } else if (containsAny(normalized, "western", "american", "north american")) {
            regions.addAll(List.of("United States", "Canada", "United Kingdom", "Australia"));
        } else if (containsAny(normalized, "thai", "vietnamese", "southeast asian")) {
            regions.addAll(List.of("Thailand", "Vietnam", "Malaysia", "Indonesia", "Philippines"));
        }

        return new ArrayList<>(regions);
    }

    public Map<String, Double> convertUsdToCurrencies(Double amountUsd, List<String> currencies) {
        double usd = amountUsd == null ? 0.0 : Math.max(0.0, amountUsd);
        List<String> targets = (currencies == null || currencies.isEmpty())
            ? List.of("INR", "USD", "EUR", "GBP")
            : currencies;

        Map<String, Double> converted = new LinkedHashMap<>();
        for (String currency : targets) {
            if (!StringUtils.hasText(currency)) {
                continue;
            }

            String key = currency.trim().toUpperCase(Locale.ROOT);
            Double rate = currencyRates.get(key);
            if (rate == null) {
                continue;
            }
            converted.put(key, CalorieMath.round(usd * rate));
        }

        return converted;
    }

    public Map<String, Double> getCurrencyRates() {
        return Collections.unmodifiableMap(currencyRates);
    }

    public String resolveIngredientImageUrl(String imageUrl, String ingredientName, IngredientCategory category, String cuisine) {
        String sanitized = sanitizeImageUrl(imageUrl);
        if (StringUtils.hasText(sanitized)) {
            return sanitized;
        }

        String categoryLabel = category == null ? "ingredient" : category.name().toLowerCase(Locale.ROOT);
        return fallbackFoodImageUrl(ingredientName, categoryLabel, cuisine, "food");
    }

    public String resolveDishImageUrl(String imageUrl, String dishName, String cuisine) {
        String sanitized = sanitizeImageUrl(imageUrl);
        if (StringUtils.hasText(sanitized)) {
            return sanitized;
        }
        return fallbackFoodImageUrl(dishName, cuisine, "dish", "meal");
    }

    public String sanitizeImageUrl(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return "";
        }

        String candidate = imageUrl.trim();
        if (candidate.startsWith("/api/media/")) {
            if (candidate.length() > MAX_IMAGE_URL_LENGTH) {
                candidate = candidate.substring(0, MAX_IMAGE_URL_LENGTH);
            }
            return candidate;
        }
        if (candidate.startsWith("api/media/")) {
            candidate = "/" + candidate;
            if (candidate.length() > MAX_IMAGE_URL_LENGTH) {
                candidate = candidate.substring(0, MAX_IMAGE_URL_LENGTH);
            }
            return candidate;
        }

        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        }

        if (!(candidate.startsWith("http://") || candidate.startsWith("https://"))) {
            return "";
        }

        if (candidate.length() > MAX_IMAGE_URL_LENGTH) {
            candidate = candidate.substring(0, MAX_IMAGE_URL_LENGTH);
        }
        return candidate;
    }

    public List<String> fromCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .distinct()
            .collect(Collectors.toList());
    }

    public String toCsv(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .distinct()
            .limit(40)
            .collect(Collectors.joining(", "));
    }

    public String normalizeToken(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replace("&", " and ")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();

        return normalized.replaceAll("\\s+", " ");
    }

    private String fallbackFoodImageUrl(String primary, String secondary, String tertiary, String type) {
        List<String> tokens = new ArrayList<>();
        tokens.add(normalizeToken(primary));
        tokens.add(normalizeToken(secondary));
        tokens.add(normalizeToken(tertiary));
        tokens.add(normalizeToken(type));

        String query = tokens.stream()
            .filter(StringUtils::hasText)
            .limit(5)
            .collect(Collectors.joining(" "));

        if (!StringUtils.hasText(query)) {
            query = "food";
        }

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return "https://source.unsplash.com/featured/360x240/?" + encoded;
    }

    private void initializeMacroProfiles() {
        macroShares.put(IngredientCategory.FRUIT, new MacroShare(0.04, 0.90, 0.06, 0.18));
        macroShares.put(IngredientCategory.VEGETABLE, new MacroShare(0.16, 0.68, 0.16, 0.24));
        macroShares.put(IngredientCategory.JUICE, new MacroShare(0.04, 0.94, 0.02, 0.05));
        macroShares.put(IngredientCategory.SNACK, new MacroShare(0.10, 0.50, 0.40, 0.10));
        macroShares.put(IngredientCategory.RICE, new MacroShare(0.08, 0.86, 0.06, 0.03));
        macroShares.put(IngredientCategory.MEAT, new MacroShare(0.52, 0.04, 0.44, 0.00));
        macroShares.put(IngredientCategory.SEAFOOD, new MacroShare(0.58, 0.03, 0.39, 0.00));
        macroShares.put(IngredientCategory.LEGUME, new MacroShare(0.24, 0.60, 0.16, 0.22));
        macroShares.put(IngredientCategory.DAIRY, new MacroShare(0.20, 0.32, 0.48, 0.00));
        macroShares.put(IngredientCategory.GRAIN, new MacroShare(0.14, 0.72, 0.14, 0.10));
        macroShares.put(IngredientCategory.SPICE, new MacroShare(0.12, 0.68, 0.20, 0.33));
        macroShares.put(IngredientCategory.BEVERAGE, new MacroShare(0.03, 0.93, 0.04, 0.04));
        macroShares.put(IngredientCategory.OIL, new MacroShare(0.00, 0.00, 1.00, 0.00));
        macroShares.put(IngredientCategory.SAUCE, new MacroShare(0.05, 0.55, 0.40, 0.08));
        macroShares.put(IngredientCategory.OTHER, new MacroShare(0.16, 0.54, 0.30, 0.12));
    }

    private void initializePriceProfiles() {
        baseCategoryPriceUsd.put(IngredientCategory.FRUIT, 4.2);
        baseCategoryPriceUsd.put(IngredientCategory.VEGETABLE, 3.1);
        baseCategoryPriceUsd.put(IngredientCategory.JUICE, 2.8);
        baseCategoryPriceUsd.put(IngredientCategory.SNACK, 8.4);
        baseCategoryPriceUsd.put(IngredientCategory.RICE, 2.4);
        baseCategoryPriceUsd.put(IngredientCategory.MEAT, 10.8);
        baseCategoryPriceUsd.put(IngredientCategory.SEAFOOD, 14.5);
        baseCategoryPriceUsd.put(IngredientCategory.LEGUME, 3.4);
        baseCategoryPriceUsd.put(IngredientCategory.DAIRY, 5.6);
        baseCategoryPriceUsd.put(IngredientCategory.GRAIN, 2.9);
        baseCategoryPriceUsd.put(IngredientCategory.SPICE, 18.0);
        baseCategoryPriceUsd.put(IngredientCategory.BEVERAGE, 2.6);
        baseCategoryPriceUsd.put(IngredientCategory.OIL, 7.6);
        baseCategoryPriceUsd.put(IngredientCategory.SAUCE, 4.8);
        baseCategoryPriceUsd.put(IngredientCategory.OTHER, 5.2);

        namePriceOverrides.put("saffron", 4200.0);
        namePriceOverrides.put("olive oil", 12.0);
        namePriceOverrides.put("ghee", 11.0);
        namePriceOverrides.put("salmon", 20.0);
        namePriceOverrides.put("tuna", 18.0);
        namePriceOverrides.put("lobster", 36.0);
        namePriceOverrides.put("shrimp", 16.0);
        namePriceOverrides.put("prawn", 16.0);
        namePriceOverrides.put("beef", 12.0);
        namePriceOverrides.put("mutton", 14.0);
        namePriceOverrides.put("goat", 15.0);
        namePriceOverrides.put("chicken", 8.0);
        namePriceOverrides.put("rice", 2.1);
        namePriceOverrides.put("basmati", 2.8);
        namePriceOverrides.put("quinoa", 6.4);
        namePriceOverrides.put("paneer", 7.2);
        namePriceOverrides.put("cheese", 9.5);
        namePriceOverrides.put("ice cream", 6.8);
        namePriceOverrides.put("cake", 12.5);
        namePriceOverrides.put("vada pav", 4.0);
    }

    private void initializeAliasVocabulary() {
        registerAliases("biryani", "biriyani", "biriani", "briyani");
        registerAliases("chickpea", "chickpeas", "chana", "garbanzo", "garbanzo bean");
        registerAliases("eggplant", "aubergine", "brinjal", "baingan");
        registerAliases("coriander", "cilantro", "dhaniya");
        registerAliases("bell pepper", "capsicum");
        registerAliases("zucchini", "courgette");
        registerAliases("shrimp", "prawn", "jhinga");
        registerAliases("yogurt", "yoghurt", "curd", "dahi");
        registerAliases("cottage cheese", "paneer");
        registerAliases("garbanzo", "chickpea");
        registerAliases("spring onion", "scallion");
        registerAliases("beetroot", "beet");
        registerAliases("sweet potato", "kumara");
        registerAliases("okra", "lady finger", "bhindi");
        registerAliases("egg", "anda");
        registerAliases("goat", "mutton");
        registerAliases("caraway", "shahi jeera");
        registerAliases("cumin", "jeera");
        registerAliases("turmeric", "haldi");
        registerAliases("fenugreek", "methi");
        registerAliases("chili", "chilli", "mirchi");
        registerAliases("plantain", "raw banana");
        registerAliases("vada pav", "vada pao", "wada pav");
        registerAliases("mixture", "mixure", "namkeen mixture", "chanachur");
        registerAliases("falafel", "felafel");
        registerAliases("shawarma", "shavarma");
        registerAliases("rice noodle", "rice vermicelli");
        registerAliases("lentil", "dal", "dhal");
        registerAliases("kidney bean", "rajma");
        registerAliases("black gram", "urad dal");
        registerAliases("pigeon pea", "toor dal", "arhar dal");
        registerAliases("mustard oil", "sarson oil");
        registerAliases("clarified butter", "ghee");
        registerAliases("semolina", "suji", "rava");
        registerAliases("garam masala", "gram masala");
        registerAliases("soy sauce", "soya sauce");
        registerAliases("flatbread", "roti", "chapati");
        registerAliases("corn tortilla", "makki roti");
        registerAliases("pulao", "pulav", "pilaf", "pilau", "pulaav");
        registerAliases("curd rice", "thayir sadam", "thayir saadham", "daddojanam", "mosaranna");
        registerAliases("lemon rice", "chitranna", "chitrannam", "nimmakaya pulihora", "elumichai sadam");
        registerAliases("tomato rice", "thakkali sadam", "tomato bath", "tomato baath");
        registerAliases("tamarind rice", "puliyodarai", "puliyogare", "pulihora", "puli sadam");
        registerAliases("veg meals", "veg meal", "vegetarian meal", "vegetarian meals", "veg thali");
        registerAliases("non veg meals", "non veg meal", "nonveg meals", "non vegetarian meal", "non vegetarian meals", "non veg thali");
        registerAliases("thali", "meal plate", "meals plate");
    }

    private void initializeCurrencyRates() {
        currencyRates.put("USD", 1.0);
        currencyRates.put("EUR", 0.92);
        currencyRates.put("INR", 83.0);
        currencyRates.put("GBP", 0.79);
        currencyRates.put("JPY", 150.0);
        currencyRates.put("CNY", 7.2);
        currencyRates.put("AUD", 1.53);
        currencyRates.put("CAD", 1.35);
        currencyRates.put("AED", 3.67);
        currencyRates.put("SAR", 3.75);
    }

    private void registerAliases(String canonical, String... aliases) {
        List<String> values = new ArrayList<>();
        values.add(canonical);
        values.addAll(Arrays.asList(aliases));

        List<String> normalized = values.stream()
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .distinct()
            .collect(Collectors.toList());
        aliasGroups.put(canonical, normalized);

        for (String alias : normalized) {
            aliasToCanonical.put(normalizeToken(alias), canonical);
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record NutritionProfile(
        Double proteinPer100g,
        Double carbsPer100g,
        Double fatsPer100g,
        Double fiberPer100g
    ) {
    }
}

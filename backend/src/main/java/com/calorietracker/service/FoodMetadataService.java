package com.calorietracker.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<String, TimedCacheEntry<String>> normalizedTokenCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TimedCacheEntry<Set<String>>> expandedTermsCache = new ConcurrentHashMap<>();
    private static final int MAX_IMAGE_URL_LENGTH = 900;
    private static final int DEFAULT_EXPANDED_TERMS = 14;
    private static final int MAX_DYNAMIC_SPELLING_VARIANTS = 12;
    private static final long NORMALIZED_TOKEN_CACHE_TTL_MS = 900_000;
    private static final int NORMALIZED_TOKEN_CACHE_MAX = 12_000;
    private static final long EXPANDED_TERMS_CACHE_TTL_MS = 300_000;
    private static final int EXPANDED_TERMS_CACHE_MAX = 4_000;
    private static final String EXTRA_ALIAS_RESOURCE = "/aliases/multilingual_food_aliases.tsv";

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
        return expandSearchTerms(search, DEFAULT_EXPANDED_TERMS);
    }

    public Set<String> expandSearchTerms(String search, int maxTerms) {
        if (!StringUtils.hasText(search)) {
            return Collections.emptySet();
        }

        int cappedMaxTerms = clampInt(maxTerms, 1, DEFAULT_EXPANDED_TERMS);
        String normalizedSearch = normalizeToken(search);
        String cacheKey = normalizedSearch + "|" + cappedMaxTerms;
        Set<String> cachedExpanded = getCachedExpandedTerms(cacheKey);
        if (cachedExpanded != null) {
            return cachedExpanded;
        }

        Set<String> terms = new LinkedHashSet<>();
        List<String> rawTokens = Arrays.stream(search.trim().split("\\s+"))
            .filter(token -> token.length() >= 2)
            .collect(Collectors.toList());

        terms.add(search.trim());
        terms.add(search.trim().replace("-", " "));
        terms.add(search.trim().replace(" ", ""));
        terms.add(normalizedSearch);
        addSpellingVariants(terms, search, 8);

        String phraseCanonical = aliasToCanonical.get(normalizedSearch);
        if (phraseCanonical != null) {
            addCanonicalAliasTerms(terms, phraseCanonical, 10);
        }

        for (String token : rawTokens) {
            terms.add(token);
            addSpellingVariants(terms, token, 4);

            String normalizedToken = normalizeToken(token);
            String canonical = aliasToCanonical.get(normalizedToken);
            if (canonical != null) {
                addCanonicalAliasTerms(terms, canonical, 6);
            }
        }

        Set<String> expanded = terms.stream()
            .map(String::trim)
            .filter(term -> term.length() >= 2)
            .limit(cappedMaxTerms)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        cacheExpandedTerms(cacheKey, expanded);
        return expanded;
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
            aliases.addAll(buildSpellingVariants(canonical, 8));
        }

        String naturalName = ingredientName.trim();
        aliases.addAll(buildSpellingVariants(naturalName, 10));
        aliases.removeIf(alias -> normalizeToken(alias).equals(normalizedName));

        if (naturalName.contains("-")) {
            aliases.add(naturalName.replace("-", " "));
        }
        if (naturalName.contains(" ")) {
            aliases.add(naturalName.replace(" ", ""));
        }

        return aliases.stream().limit(18).collect(Collectors.toList());
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

        String cacheKey = text.trim();
        String cached = getCachedNormalizedToken(cacheKey);
        if (cached != null) {
            return cached;
        }

        String normalized = Normalizer.normalize(cacheKey, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replace("&", " and ")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();

        normalized = normalized.replaceAll("\\s+", " ");
        cacheNormalizedToken(cacheKey, normalized);
        return normalized;
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
        registerAliases("indian", "desi", "bharatiya", "hindustani");
        registerAliases("italian", "italia", "italiano");
        registerAliases("eastern", "oriental", "east asian", "far east");
        registerAliases("western", "continental", "occidental", "west style");

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
        registerAliases("dosa", "dosai", "dose", "dosha");
        registerAliases("idli", "idly", "iddly");
        registerAliases("idiyappam", "string hopper", "nool puttu", "sevai");
        registerAliases("waffle", "waffles");
        registerAliases("pancake", "pancakes", "hotcake");
        registerAliases("scrambled egg", "scramble egg", "egg scramble");
        registerAliases("bacon", "bacon strip", "bacon strips");
        registerAliases("curd rice", "thayir sadam", "thayir saadham", "daddojanam", "mosaranna");
        registerAliases("lemon rice", "chitranna", "chitrannam", "nimmakaya pulihora", "elumichai sadam");
        registerAliases("tomato rice", "thakkali sadam", "tomato bath", "tomato baath");
        registerAliases("tamarind rice", "puliyodarai", "puliyogare", "pulihora", "puli sadam");
        registerAliases("khichdi", "khichri", "kitchari", "kichdi", "kichari");
        registerAliases("upma", "uppuma", "uppittu", "upittu");
        registerAliases("poha", "aval", "avalakki", "chivda");
        registerAliases("uttapam", "uthappam", "oothappam", "utthappam");
        registerAliases("paratha", "parantha", "prantha", "parotta", "porotta");
        registerAliases("pakora", "pakoda", "bhajiya", "bajji");
        registerAliases("payasam", "kheer", "phirni");
        registerAliases("sambar", "sambhar", "sambaar", "saaru");
        registerAliases("rasam", "rassam", "saar");
        registerAliases("chole", "chana masala", "chole masala", "choley", "cholay");
        registerAliases("rajma chawal", "rajma rice", "kidney bean rice");
        registerAliases("appam", "hopper", "hoppers", "palappam");
        registerAliases("pav bhaji", "bhaji pav", "pao bhaji");

        registerAliases("pizza", "piza", "pitsa", "pizzeria pizza");
        registerAliases("pasta", "macaroni", "maccheroni", "noodle pasta");
        registerAliases("spaghetti", "spagetti", "spagheti");
        registerAliases("lasagna", "lasagne", "lasanya");
        registerAliases("ravioli", "raviolo");
        registerAliases("tortellini", "tortelini");
        registerAliases("gnocchi", "nyokki", "nocchi");
        registerAliases("risotto", "rizotto");
        registerAliases("focaccia", "focacia");
        registerAliases("bruschetta", "bruscetta", "brusheta");
        registerAliases("parmigiana", "parmesan bake", "melanzane parmigiana");
        registerAliases("bolognese", "bolognaise", "ragu", "ragu alla bolognese");
        registerAliases("caprese", "insalata caprese", "caprese salad");
        registerAliases("minestrone", "minestron");
        registerAliases("gelato", "gelati", "italian ice cream");
        registerAliases("tiramisu", "tiramisu cake");

        registerAliases("fried rice", "fride rice", "egg fried rice", "chao fan");
        registerAliases("noodles", "noodle", "mein", "chow mein", "chowmein", "hakka noodles");
        registerAliases("chow mein", "chowmein", "chao mein");
        registerAliases("hakka noodles", "hakka noodle", "hakka chowmein");
        registerAliases("chicken noodles", "chicken noodle", "chicken noodle stir fry", "chicken hakka noodles", "chicken chow mein", "chicken chowmein");
        registerAliases("chilli chicken", "chili chicken", "chilly chicken", "chicken chilli", "chicken chili", "chilli chicken dry", "chilli chicken gravy");
        registerAliases("manchurian", "manchuria", "manchuri");
        registerAliases("dim sum", "dimsum", "yum cha");
        registerAliases("congee", "jook", "zhou", "kanji rice porridge");
        registerAliases("ramen", "lamen", "raman");
        registerAliases("sushi", "maki", "nigiri", "sashimi");
        registerAliases("kimchi", "gimchi", "kimchee");
        registerAliases("bibimbap", "bibim bap", "bibimbaap");
        registerAliases("teriyaki", "teriaki");
        registerAliases("satay", "sate", "sat e");
        registerAliases("pho", "pho bo", "pho ga");
        registerAliases("pad thai", "phad thai", "pat thai");
        registerAliases("tom yum", "tomyum", "tom yam");

        registerAliases("burger", "hamburger", "veggie burger", "sandwich burger");
        registerAliases("french fries", "fries", "chips", "potato fries");
        registerAliases("hot dog", "hotdog", "frankfurter roll");
        registerAliases("mashed potato", "mashed potatoes", "potato mash");
        registerAliases("mac and cheese", "mac n cheese", "macaroni cheese");
        registerAliases("grilled cheese", "cheese toastie", "cheese toasty");
        registerAliases("donut", "doughnut", "dounut");
        registerAliases("oatmeal", "porridge", "oat porridge");

        registerAliases("veg meals", "veg meal", "vegetarian meal", "vegetarian meals", "veg thali");
        registerAliases("non veg meals", "non veg meal", "nonveg meals", "non vegetarian meal", "non vegetarian meals", "non veg thali");
        registerAliases("thali", "meal plate", "meals plate");

        loadAliasVocabularyFromResource(EXTRA_ALIAS_RESOURCE);
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
        if (!StringUtils.hasText(canonical)) {
            return;
        }

        List<String> values = new ArrayList<>();
        String canonicalName = canonical.trim();
        values.add(canonicalName);
        values.addAll(aliasGroups.getOrDefault(canonicalName, List.of()));
        values.addAll(Arrays.asList(aliases));

        List<String> normalized = values.stream()
            .map(String::trim)
            .filter(token -> !token.isBlank())
            .distinct()
            .collect(Collectors.toList());
        aliasGroups.put(canonicalName, normalized);

        for (String alias : normalized) {
            String normalizedAlias = normalizeToken(alias);
            if (StringUtils.hasText(normalizedAlias)) {
                aliasToCanonical.put(normalizedAlias, canonicalName);
            }
        }
    }

    private void addCanonicalAliasTerms(Set<String> terms, String canonical, int maxAliasesToUse) {
        if (!StringUtils.hasText(canonical)) {
            return;
        }

        terms.add(canonical.trim());
        addSpellingVariants(terms, canonical, 4);

        int used = 0;
        for (String alias : aliasGroups.getOrDefault(canonical, List.of())) {
            if (!StringUtils.hasText(alias)) {
                continue;
            }
            terms.add(alias.trim());
            addSpellingVariants(terms, alias, 3);
            used++;
            if (used >= Math.max(1, maxAliasesToUse)) {
                break;
            }
        }
    }

    private void addSpellingVariants(Set<String> target, String value, int maxVariants) {
        if (target == null || maxVariants <= 0) {
            return;
        }
        for (String variant : buildSpellingVariants(value, maxVariants)) {
            target.add(variant);
        }
    }

    private List<String> buildSpellingVariants(String value, int maxVariants) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }

        int cappedMax = clampInt(maxVariants, 1, MAX_DYNAMIC_SPELLING_VARIANTS);
        String normalized = normalizeToken(value);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }

        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalized);
        if (normalized.contains(" ")) {
            variants.add(normalized.replace(" ", ""));
            variants.add(normalized.replace(" ", "-"));
        }

        Set<String> baseTokenVariants = buildTokenSpellingVariants(normalized);
        variants.addAll(baseTokenVariants);

        String[] tokens = normalized.split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            Set<String> tokenVariants = buildTokenSpellingVariants(tokens[index]);
            for (String tokenVariant : tokenVariants) {
                if (!StringUtils.hasText(tokenVariant) || tokenVariant.equals(tokens[index])) {
                    continue;
                }
                String[] copy = Arrays.copyOf(tokens, tokens.length);
                copy[index] = tokenVariant;
                variants.add(String.join(" ", copy));
            }
        }

        return variants.stream()
            .map(String::trim)
            .filter(candidate -> candidate.length() >= 2)
            .limit(cappedMax)
            .collect(Collectors.toList());
    }

    private Set<String> buildTokenSpellingVariants(String token) {
        Set<String> variants = new LinkedHashSet<>();
        String normalized = normalizeToken(token);
        if (!StringUtils.hasText(normalized)) {
            return variants;
        }
        variants.add(normalized);

        applyReplacementVariants(variants, "iy", "y");
        applyReplacementVariants(variants, "yi", "i");
        applyReplacementVariants(variants, "ia", "ya");
        applyReplacementVariants(variants, "ya", "ia");
        applyReplacementVariants(variants, "ya", "iya");
        applyReplacementVariants(variants, "ia", "iya");
        applyReplacementVariants(variants, "iya", "ya");
        applyReplacementVariants(variants, "iya", "ia");
        applyReplacementVariants(variants, "ie", "i");
        applyReplacementVariants(variants, "ei", "i");
        applyReplacementVariants(variants, "ee", "i");
        applyReplacementVariants(variants, "aa", "a");
        applyReplacementVariants(variants, "oo", "u");
        applyReplacementVariants(variants, "ou", "u");
        applyReplacementVariants(variants, "ck", "k");
        applyReplacementVariants(variants, "q", "k");
        applyReplacementVariants(variants, "ph", "f");
        applyReplacementVariants(variants, "w", "v");
        applyReplacementVariants(variants, "v", "w");
        applyReplacementVariants(variants, "z", "s");
        applyReplacementVariants(variants, "s", "z");
        applyReplacementVariants(variants, "chili", "chilli");
        applyReplacementVariants(variants, "chilli", "chili");
        applyReplacementVariants(variants, "th", "t");
        applyReplacementVariants(variants, "dh", "d");
        applyReplacementVariants(variants, "bh", "b");
        applyReplacementVariants(variants, "gh", "g");
        applyReplacementVariants(variants, "kh", "k");
        applyReplacementVariants(variants, "ll", "l");
        applyReplacementVariants(variants, "tt", "t");
        applyReplacementVariants(variants, "rr", "r");
        applyReplacementVariants(variants, "nn", "n");

        List<String> snapshot = new ArrayList<>(variants);
        for (String candidate : snapshot) {
            if (candidate.length() > 4 && candidate.endsWith("ies")) {
                variants.add(candidate.substring(0, candidate.length() - 3) + "y");
            }
            if (candidate.length() > 4 && candidate.endsWith("es")) {
                variants.add(candidate.substring(0, candidate.length() - 2));
            }
            if (candidate.length() > 3 && candidate.endsWith("s") && !candidate.endsWith("ss")) {
                variants.add(candidate.substring(0, candidate.length() - 1));
            }
            if (candidate.length() > 3 && candidate.endsWith("y")) {
                variants.add(candidate.substring(0, candidate.length() - 1) + "i");
                variants.add(candidate.substring(0, candidate.length() - 1) + "ie");
            }
            if (candidate.length() > 3 && candidate.endsWith("i")) {
                variants.add(candidate.substring(0, candidate.length() - 1) + "y");
            }
            if (candidate.length() > 4 && candidate.endsWith("ie")) {
                variants.add(candidate.substring(0, candidate.length() - 2) + "y");
            }
        }

        return variants.stream()
            .map(String::trim)
            .filter(candidate -> candidate.length() >= 2)
            .limit(MAX_DYNAMIC_SPELLING_VARIANTS)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void applyReplacementVariants(Set<String> variants, String from, String to) {
        if (variants == null || !StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            return;
        }
        List<String> snapshot = new ArrayList<>(variants);
        for (String candidate : snapshot) {
            if (candidate.contains(from)) {
                variants.add(candidate.replace(from, to));
            }
        }
    }

    private void loadAliasVocabularyFromResource(String resourcePath) {
        if (!StringUtils.hasText(resourcePath)) {
            return;
        }

        InputStream input = FoodMetadataService.class.getResourceAsStream(resourcePath);
        if (input == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                String[] parts = trimmed.split("\t", 2);
                if (!StringUtils.hasText(parts[0])) {
                    continue;
                }

                String canonical = parts[0].trim();
                if (parts.length == 1 || !StringUtils.hasText(parts[1])) {
                    registerAliases(canonical);
                    continue;
                }

                String[] aliases = Arrays.stream(parts[1].split("\\|"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toArray(String[]::new);
                registerAliases(canonical, aliases);
            }
        } catch (IOException ignored) {
            // Ignore optional external vocabulary load failures.
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

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getCachedNormalizedToken(String key) {
        TimedCacheEntry<String> entry = normalizedTokenCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.cachedAtMs() > NORMALIZED_TOKEN_CACHE_TTL_MS) {
            normalizedTokenCache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private void cacheNormalizedToken(String key, String value) {
        normalizedTokenCache.put(key, new TimedCacheEntry<>(value, System.currentTimeMillis()));
        if (normalizedTokenCache.size() > NORMALIZED_TOKEN_CACHE_MAX) {
            pruneOldestCacheEntry(normalizedTokenCache);
        }
    }

    private Set<String> getCachedExpandedTerms(String key) {
        TimedCacheEntry<Set<String>> entry = expandedTermsCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.cachedAtMs() > EXPANDED_TERMS_CACHE_TTL_MS) {
            expandedTermsCache.remove(key, entry);
            return null;
        }
        return new LinkedHashSet<>(entry.value());
    }

    private void cacheExpandedTerms(String key, Set<String> value) {
        expandedTermsCache.put(key, new TimedCacheEntry<>(Collections.unmodifiableSet(new LinkedHashSet<>(value)), System.currentTimeMillis()));
        if (expandedTermsCache.size() > EXPANDED_TERMS_CACHE_MAX) {
            pruneOldestCacheEntry(expandedTermsCache);
        }
    }

    private <T> void pruneOldestCacheEntry(ConcurrentMap<String, TimedCacheEntry<T>> cache) {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, TimedCacheEntry<T>> entry : cache.entrySet()) {
            if (entry.getValue().cachedAtMs() < oldestTime) {
                oldestTime = entry.getValue().cachedAtMs();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    private record TimedCacheEntry<T>(T value, long cachedAtMs) {
    }

    public record NutritionProfile(
        Double proteinPer100g,
        Double carbsPer100g,
        Double fatsPer100g,
        Double fiberPer100g
    ) {
    }
}

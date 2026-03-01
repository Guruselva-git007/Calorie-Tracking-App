package com.calorietracker.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.DishResponse;
import com.calorietracker.dto.IngredientResponse;

@Service
public class VoiceFoodResolveService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final int DEFAULT_LIMIT = 8;
    private static final int MIN_LIMIT = 4;
    private static final int MAX_LIMIT = 12;

    private final IngredientService ingredientService;
    private final DishService dishService;

    public VoiceFoodResolveService(
        IngredientService ingredientService,
        DishService dishService
    ) {
        this.ingredientService = ingredientService;
        this.dishService = dishService;
    }

    public Map<String, Object> resolve(String rawQuery, Integer limit) {
        String query = sanitizeVoiceQuery(rawQuery);
        int maxResults = resolveLimit(limit);

        if (!StringUtils.hasText(query)) {
            return Map.of(
                "found", false,
                "query", "",
                "message", "Speak a food name to search."
            );
        }

        List<IngredientResponse> ingredientCandidates = fetchIngredientCandidates(query, maxResults);
        List<DishResponse> dishCandidates = fetchDishCandidates(query, maxResults);

        List<ScoredCandidate<IngredientResponse>> rankedIngredients = ingredientCandidates.stream()
            .map(candidate -> new ScoredCandidate<>(candidate, scoreIngredient(candidate, query)))
            .sorted((left, right) -> Double.compare(right.score(), left.score()))
            .collect(Collectors.toList());

        List<ScoredCandidate<DishResponse>> rankedDishes = dishCandidates.stream()
            .map(candidate -> new ScoredCandidate<>(candidate, scoreDish(candidate, query)))
            .sorted((left, right) -> Double.compare(right.score(), left.score()))
            .collect(Collectors.toList());

        List<IngredientResponse> ingredients = rankedIngredients.stream()
            .map(ScoredCandidate::item)
            .limit(maxResults)
            .collect(Collectors.toList());
        List<DishResponse> dishes = rankedDishes.stream()
            .map(ScoredCandidate::item)
            .limit(maxResults)
            .collect(Collectors.toList());

        ScoredCandidate<IngredientResponse> bestIngredient = rankedIngredients.stream().findFirst().orElse(null);
        ScoredCandidate<DishResponse> bestDish = rankedDishes.stream().findFirst().orElse(null);

        String bestType = "";
        double bestScore = 0.0;
        if (bestDish != null) {
            bestType = "dish";
            bestScore = bestDish.score();
        }
        if (bestIngredient != null && bestIngredient.score() >= bestScore) {
            bestType = "ingredient";
            bestScore = bestIngredient.score();
        }

        boolean found = !ingredients.isEmpty() || !dishes.isEmpty();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", found);
        response.put("query", query);
        response.put("bestType", bestType);
        response.put("bestScore", roundScore(bestScore));
        response.put("bestIngredient", bestIngredient == null ? null : bestIngredient.item());
        response.put("bestDish", bestDish == null ? null : bestDish.item());
        response.put("ingredients", ingredients);
        response.put("dishes", dishes);
        response.put(
            "message",
            found
                ? "Voice match ready. Continue hands-free or confirm from suggestions."
                : "No strong match yet. Try a clearer food name."
        );

        return response;
    }

    private List<IngredientResponse> fetchIngredientCandidates(String query, int maxResults) {
        try {
            return ingredientService.findIngredients(query, null, null, maxResults);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<DishResponse> fetchDishCandidates(String query, int maxResults) {
        try {
            return dishService.findDishSuggestions(query, maxResults);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double scoreIngredient(IngredientResponse ingredient, String query) {
        String normalizedQuery = normalize(query);
        String normalizedName = normalize(ingredient.getName());

        double best = Math.max(
            phraseScore(normalizedName, normalizedQuery),
            tokenOverlapScore(buildIngredientText(ingredient), normalizedQuery)
        );

        List<String> aliases = ingredient.getAliases() == null ? List.of() : ingredient.getAliases();
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            best = Math.max(best, phraseScore(normalizedAlias, normalizedQuery) * 0.98);
            best = Math.max(best, tokenOverlapScore(normalizedAlias, normalizedQuery) * 0.94);
        }

        return clamp(best);
    }

    private double scoreDish(DishResponse dish, String query) {
        String normalizedQuery = normalize(query);
        String normalizedName = normalize(dish.getName());

        double best = Math.max(
            phraseScore(normalizedName, normalizedQuery),
            tokenOverlapScore(buildDishText(dish), normalizedQuery)
        );

        return clamp(best);
    }

    private String buildIngredientText(IngredientResponse ingredient) {
        List<String> parts = new ArrayList<>();
        parts.add(ingredient.getName());
        parts.add(ingredient.getCuisine());
        parts.add(ingredient.getCategory() == null ? "" : ingredient.getCategory().name());
        if (ingredient.getAliases() != null && !ingredient.getAliases().isEmpty()) {
            parts.add(String.join(" ", ingredient.getAliases().stream().limit(10).collect(Collectors.toList())));
        }
        return normalize(String.join(" ", parts));
    }

    private String buildDishText(DishResponse dish) {
        return normalize(String.join(" ",
            safe(dish.getName()),
            safe(dish.getCuisine()),
            safe(dish.getDescription())
        ));
    }

    private String sanitizeVoiceQuery(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        String cleaned = value.trim()
            .replaceAll("(?i)^(please\\s+)?(add|log|track|calculate|search|find)\\s+", "")
            .replaceAll("(?i)\\s+(please|now)$", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cleaned;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    private double phraseScore(String candidate, String query) {
        if (!StringUtils.hasText(candidate) || !StringUtils.hasText(query)) {
            return 0.0;
        }
        if (candidate.equals(query)) {
            return 1.0;
        }
        if (candidate.startsWith(query + " ") || candidate.contains(" " + query + " ")) {
            return 0.96;
        }
        if (candidate.startsWith(query) || query.startsWith(candidate)) {
            return 0.9;
        }
        if (candidate.contains(query) || query.contains(candidate)) {
            return 0.82;
        }
        return 0.0;
    }

    private double tokenOverlapScore(String candidate, String query) {
        Set<String> candidateTokens = tokenize(candidate);
        Set<String> queryTokens = tokenize(query);
        if (candidateTokens.isEmpty() || queryTokens.isEmpty()) {
            return 0.0;
        }

        long matched = queryTokens.stream().filter(candidateTokens::contains).count();
        if (matched <= 0) {
            return 0.0;
        }

        double coverage = matched / (double) queryTokens.size();
        if (coverage >= 1.0) {
            return 0.9;
        }
        return 0.46 + coverage * 0.38;
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(normalize(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double roundScore(double value) {
        return Math.round(clamp(value) * 1000.0) / 1000.0;
    }

    private record ScoredCandidate<T>(T item, double score) {
    }
}

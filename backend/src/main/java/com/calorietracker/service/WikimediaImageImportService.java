package com.calorietracker.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.calorietracker.entity.Dish;
import com.calorietracker.entity.Ingredient;
import com.calorietracker.repository.DishRepository;
import com.calorietracker.repository.IngredientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Service
public class WikimediaImageImportService {

    private static final Logger log = LoggerFactory.getLogger(WikimediaImageImportService.class);
    private static final String COMMONS_SEARCH_ENDPOINT = "https://commons.wikimedia.org/w/api.php";
    private static final String USER_AGENT = "CalorieTrackerImageImporter/1.0 (scholarship project)";
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final int MAX_INGREDIENT_QUERIES = 3;
    private static final int MAX_DISH_QUERIES = 6;
    private static final Set<String> NOISE_TOKENS = Set.of(
        "and", "with", "without", "fresh", "whole", "pure", "pack", "mix", "powder", "sugar",
        "added", "zero", "old", "fashioned", "mini", "maxi", "value", "style", "flavor"
    );

    private final IngredientRepository ingredientRepository;
    private final DishRepository dishRepository;
    private final FoodMetadataService foodMetadataService;
    private final ObjectMapper objectMapper;
    private final Path mediaRoot;
    private final String mediaApiPrefix;
    private final int maxImageBytes;
    private final Map<String, Optional<ImageCandidate>> searchCache = new ConcurrentHashMap<>();

    public WikimediaImageImportService(
        IngredientRepository ingredientRepository,
        DishRepository dishRepository,
        FoodMetadataService foodMetadataService,
        ObjectMapper objectMapper,
        @Value("${app.media.root:media}") String mediaRoot,
        @Value("${app.media.api-prefix:/api/media}") String mediaApiPrefix,
        @Value("${app.media.max-image-bytes:5242880}") Integer maxImageBytes
    ) {
        this.ingredientRepository = ingredientRepository;
        this.dishRepository = dishRepository;
        this.foodMetadataService = foodMetadataService;
        this.objectMapper = objectMapper;
        this.mediaRoot = Paths.get(mediaRoot).toAbsolutePath().normalize();
        this.mediaApiPrefix = normalizeMediaApiPrefix(mediaApiPrefix);
        this.maxImageBytes = maxImageBytes == null ? 5_242_880 : Math.max(262_144, maxImageBytes);
    }

    @PostConstruct
    public void initializeDirectories() {
        ensureDirectory(mediaRoot.resolve("ingredients"));
        ensureDirectory(mediaRoot.resolve("dishes"));
    }

    public Map<String, Object> importImages(
        Boolean includeIngredients,
        Boolean includeDishes,
        Integer ingredientLimit,
        Integer dishLimit,
        Boolean overwriteExisting
    ) {
        searchCache.clear();

        boolean includeIngredientRecords = Boolean.TRUE.equals(includeIngredients);
        boolean includeDishRecords = Boolean.TRUE.equals(includeDishes);
        boolean replaceExisting = Boolean.TRUE.equals(overwriteExisting);

        int safeIngredientLimit = clampLimit(ingredientLimit, 0, 5000, 1200);
        int safeDishLimit = clampLimit(dishLimit, 0, 5000, 1200);

        ImageImportStats ingredientStats = includeIngredientRecords
            ? importIngredientImages(safeIngredientLimit, replaceExisting)
            : ImageImportStats.skipped("ingredients");

        ImageImportStats dishStats = includeDishRecords
            ? importDishImages(safeDishLimit, replaceExisting)
            : ImageImportStats.skipped("dishes");

        int totalUpdated = ingredientStats.updated + dishStats.updated;
        int totalFailed = ingredientStats.failed + dishStats.failed;
        int totalNoMatch = ingredientStats.noMatch + dishStats.noMatch;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ingredients", ingredientStats.toMap());
        summary.put("dishes", dishStats.toMap());
        summary.put("totalUpdated", totalUpdated);
        summary.put("totalFailed", totalFailed);
        summary.put("totalNoMatch", totalNoMatch);
        summary.put("mediaRoot", mediaRoot.toString());

        return Map.of("summary", summary);
    }

    private ImageImportStats importIngredientImages(int limit, boolean overwriteExisting) {
        ImageImportStats stats = new ImageImportStats("ingredients");
        if (limit <= 0) {
            return stats;
        }

        List<Ingredient> ingredients = ingredientRepository.findAll(
            PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "name"))
        ).getContent();

        List<Ingredient> changed = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            stats.scanned++;
            if (!shouldAttemptImport(ingredient.getImageUrl(), overwriteExisting)) {
                stats.skipped++;
                continue;
            }

            Optional<ImageCandidate> candidate = findBestImageCandidate(buildIngredientQueries(ingredient));
            if (candidate.isEmpty()) {
                stats.noMatch++;
                continue;
            }

            Optional<DownloadedImage> downloadedImage = downloadImage(candidate.get());
            if (downloadedImage.isEmpty()) {
                stats.failed++;
                continue;
            }

            String extension = resolveExtension(downloadedImage.get().mimeType, candidate.get().url);
            String fileName = buildFileName("ingredient", ingredient.getId(), ingredient.getName(), extension);
            Path target = mediaRoot.resolve("ingredients").resolve(fileName).normalize();
            if (!target.startsWith(mediaRoot.resolve("ingredients"))) {
                stats.failed++;
                continue;
            }

            try {
                Files.write(
                    target,
                    downloadedImage.get().bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
                ingredient.setImageUrl(buildPublicMediaUrl("ingredients", fileName));
                changed.add(ingredient);
                stats.updated++;
            } catch (IOException ioException) {
                log.warn("Failed to store ingredient image for {}", ingredient.getName(), ioException);
                stats.failed++;
            }
        }

        if (!changed.isEmpty()) {
            ingredientRepository.saveAll(changed);
        }

        return stats;
    }

    private ImageImportStats importDishImages(int limit, boolean overwriteExisting) {
        ImageImportStats stats = new ImageImportStats("dishes");
        if (limit <= 0) {
            return stats;
        }

        List<Dish> dishes = dishRepository.findAll(
            PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "name"))
        ).getContent();

        List<Dish> changed = new ArrayList<>();
        for (Dish dish : dishes) {
            stats.scanned++;
            if (!shouldAttemptImport(dish.getImageUrl(), overwriteExisting)) {
                stats.skipped++;
                continue;
            }

            Optional<ImageCandidate> candidate = findBestImageCandidate(buildDishQueries(dish));
            if (candidate.isEmpty()) {
                stats.noMatch++;
                continue;
            }

            Optional<DownloadedImage> downloadedImage = downloadImage(candidate.get());
            if (downloadedImage.isEmpty()) {
                stats.failed++;
                continue;
            }

            String extension = resolveExtension(downloadedImage.get().mimeType, candidate.get().url);
            String fileName = buildFileName("dish", dish.getId(), dish.getName(), extension);
            Path target = mediaRoot.resolve("dishes").resolve(fileName).normalize();
            if (!target.startsWith(mediaRoot.resolve("dishes"))) {
                stats.failed++;
                continue;
            }

            try {
                Files.write(
                    target,
                    downloadedImage.get().bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
                dish.setImageUrl(buildPublicMediaUrl("dishes", fileName));
                changed.add(dish);
                stats.updated++;
            } catch (IOException ioException) {
                log.warn("Failed to store dish image for {}", dish.getName(), ioException);
                stats.failed++;
            }
        }

        if (!changed.isEmpty()) {
            dishRepository.saveAll(changed);
        }

        return stats;
    }

    private Optional<ImageCandidate> findBestImageCandidate(List<String> queries) {
        for (String query : queries) {
            String normalized = foodMetadataService.normalizeToken(query);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }

            Optional<ImageCandidate> cached = searchCache.get(normalized);
            if (cached != null) {
                if (cached.isPresent()) {
                    return cached;
                }
                continue;
            }

            Optional<ImageCandidate> fetched = fetchFromWikimediaCommons(query);
            searchCache.put(normalized, fetched);
            if (fetched.isPresent()) {
                return fetched;
            }
        }
        return Optional.empty();
    }

    private Optional<ImageCandidate> fetchFromWikimediaCommons(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = URI.create(
                COMMONS_SEARCH_ENDPOINT
                    + "?action=query&format=json&formatversion=2&generator=search&gsrnamespace=6"
                    + "&gsrlimit=8&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Cthumburl&iiurlwidth=720&gsrsearch="
                    + encodedQuery
            );

            Optional<String> responseBody = readTextResponse(uri, 1500, 2500);
            if (responseBody.isEmpty()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(responseBody.get());
            JsonNode pages = root.path("query").path("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                return Optional.empty();
            }

            List<ImageCandidate> candidates = new ArrayList<>();
            for (JsonNode page : pages) {
                JsonNode imageInfo = page.path("imageinfo");
                if (!imageInfo.isArray() || imageInfo.isEmpty()) {
                    continue;
                }

                JsonNode details = imageInfo.get(0);
                String url = details.path("url").asText("");
                String thumbUrl = details.path("thumburl").asText("");
                String downloadUrl = StringUtils.hasText(thumbUrl) ? thumbUrl : url;
                String mimeType = normalizeMimeType(details.path("mime").asText(""));
                long size = details.path("size").asLong(0L);
                String title = page.path("title").asText("");

                if (!isSupportedMimeType(mimeType)) {
                    continue;
                }
                if (!StringUtils.hasText(downloadUrl)) {
                    continue;
                }
                if (!StringUtils.hasText(thumbUrl) && size > maxImageBytes * 2L) {
                    continue;
                }

                candidates.add(new ImageCandidate(downloadUrl, mimeType, title));
            }

            return candidates.stream()
                .max(Comparator.comparingInt(this::scoreCandidate));
        } catch (Exception exception) {
            log.debug("Wikimedia image search failed for query={}", query, exception);
            return Optional.empty();
        }
    }

    private Optional<DownloadedImage> downloadImage(ImageCandidate candidate) {
        try {
            Optional<DownloadedImage> downloaded = readBinaryResponse(URI.create(candidate.url), 2000, 3500);
            if (downloaded.isEmpty()) {
                return Optional.empty();
            }

            byte[] body = downloaded.get().bytes;
            if (body == null || body.length < 512 || body.length > maxImageBytes) {
                return Optional.empty();
            }

            String mimeType = normalizeMimeType(downloaded.get().mimeType);
            if (!StringUtils.hasText(mimeType)) {
                mimeType = normalizeMimeType(candidate.mimeType);
            }

            if (!isSupportedMimeType(mimeType)) {
                return Optional.empty();
            }

            return Optional.of(new DownloadedImage(body, mimeType));
        } catch (Exception exception) {
            log.debug("Image download failed for {}", candidate.url, exception);
            return Optional.empty();
        }
    }

    private Optional<String> readTextResponse(URI uri, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return Optional.empty();
            }

            try (InputStream stream = connection.getInputStream()) {
                byte[] bytes = stream.readAllBytes();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Optional<DownloadedImage> readBinaryResponse(URI uri, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return Optional.empty();
            }

            String mimeType = normalizeMimeType(connection.getContentType());
            try (InputStream stream = connection.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > maxImageBytes) {
                        return Optional.empty();
                    }
                    output.write(buffer, 0, read);
                }
                return Optional.of(new DownloadedImage(output.toByteArray(), mimeType));
            }
        } catch (Exception exception) {
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<String> buildIngredientQueries(Ingredient ingredient) {
        Set<String> queries = new LinkedHashSet<>();
        String name = ingredient.getName();
        String cuisine = ingredient.getCuisine();
        String category = ingredient.getCategory() == null ? "ingredient" : ingredient.getCategory().name().toLowerCase(Locale.ROOT);
        String base = buildSearchSeed(name, "ingredient", true);

        addQuery(queries, base + " ingredient");
        addQuery(queries, base + " " + category + " food");
        if (StringUtils.hasText(cuisine) && !"global".equalsIgnoreCase(cuisine.trim())) {
            addQuery(queries, base + " " + cuisine + " food");
        }

        return new ArrayList<>(queries).subList(0, Math.min(queries.size(), MAX_INGREDIENT_QUERIES));
    }

    private List<String> buildDishQueries(Dish dish) {
        Set<String> queries = new LinkedHashSet<>();
        String name = dish.getName();
        String cuisine = dish.getCuisine();
        String base = buildSearchSeed(name, "dish", true);
        String relaxed = buildSearchSeed(name, base, false);
        String description = buildSearchSeed(dish.getDescription(), "", true);

        addQuery(queries, base + " dish");
        addQuery(queries, base + " meal");
        addQuery(queries, relaxed + " recipe");
        addQuery(queries, base + " recipe");
        if (StringUtils.hasText(cuisine) && !"global".equalsIgnoreCase(cuisine.trim())) {
            addQuery(queries, base + " " + cuisine + " dish");
            addQuery(queries, relaxed + " " + cuisine + " recipe");
        }
        if (StringUtils.hasText(description)) {
            addQuery(queries, description + " recipe");
        }

        return new ArrayList<>(queries).subList(0, Math.min(queries.size(), MAX_DISH_QUERIES));
    }

    private String buildSearchSeed(String rawName, String fallback, boolean strict) {
        String normalized = foodMetadataService.normalizeToken(rawName);
        if (!StringUtils.hasText(normalized)) {
            return fallback;
        }

        List<String> tokens = Arrays.stream(normalized.split(" "))
            .map(String::trim)
            .filter(token -> token.length() >= (strict ? 3 : 2))
            .filter(token -> !strict || token.chars().noneMatch(Character::isDigit))
            .filter(token -> !strict || !NOISE_TOKENS.contains(token))
            .limit(3)
            .toList();

        if (tokens.isEmpty()) {
            return fallback;
        }

        return String.join(" ", tokens);
    }

    private void addQuery(Set<String> queries, String query) {
        if (!StringUtils.hasText(query)) {
            return;
        }
        String compact = query.trim().replaceAll("\\s+", " ");
        if (compact.length() >= 3) {
            queries.add(compact);
        }
    }

    private String buildFileName(String kind, Long id, String name, String extension) {
        String slug = foodMetadataService.normalizeToken(name).replace(" ", "-");
        slug = slug.replaceAll("[^a-z0-9-]", "");
        if (!StringUtils.hasText(slug)) {
            slug = kind;
        }
        if (slug.length() > 64) {
            slug = slug.substring(0, 64);
        }
        return id + "-" + slug + "." + extension;
    }

    private String buildPublicMediaUrl(String type, String fileName) {
        return mediaApiPrefix + "/" + type + "/" + fileName;
    }

    private boolean shouldAttemptImport(String imageUrl, boolean overwriteExisting) {
        if (!StringUtils.hasText(imageUrl)) {
            return true;
        }

        String normalized = imageUrl.trim().toLowerCase(Locale.ROOT);
        boolean localMedia = normalized.startsWith(mediaApiPrefix.toLowerCase(Locale.ROOT) + "/");
        boolean fallbackImage = normalized.contains("source.unsplash.com");

        if (fallbackImage) {
            return true;
        }
        if (overwriteExisting) {
            return !localMedia;
        }
        return false;
    }

    private String resolveExtension(String mimeType, String url) {
        String normalizedMime = normalizeMimeType(mimeType);
        if ("image/png".equals(normalizedMime)) {
            return "png";
        }
        if ("image/webp".equals(normalizedMime)) {
            return "webp";
        }

        String lowerUrl = String.valueOf(url).toLowerCase(Locale.ROOT);
        if (lowerUrl.endsWith(".png")) {
            return "png";
        }
        if (lowerUrl.endsWith(".webp")) {
            return "webp";
        }
        return "jpg";
    }

    private int scoreCandidate(ImageCandidate candidate) {
        String title = String.valueOf(candidate.title).toLowerCase(Locale.ROOT);
        String url = String.valueOf(candidate.url).toLowerCase(Locale.ROOT);
        int score = 0;

        if (title.contains("logo") || title.contains("icon") || title.contains("symbol")) {
            score -= 8;
        }
        if (title.contains("food") || title.contains("dish") || title.contains("meal")) {
            score += 3;
        }
        if (url.endsWith(".jpg") || url.endsWith(".jpeg")) {
            score += 2;
        } else if (url.endsWith(".webp")) {
            score += 1;
        }

        return score;
    }

    private String normalizeMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "";
        }
        return mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    private boolean isSupportedMimeType(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(normalizeMimeType(mimeType));
    }

    private int clampLimit(Integer value, int min, int max, int fallback) {
        int candidate = value == null ? fallback : value;
        return Math.max(min, Math.min(candidate, max));
    }

    private String normalizeMediaApiPrefix(String value) {
        if (!StringUtils.hasText(value)) {
            return "/api/media";
        }

        String prefix = value.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create media folder: " + path, exception);
        }
    }

    private record ImageCandidate(String url, String mimeType, String title) {
    }

    private record DownloadedImage(byte[] bytes, String mimeType) {
    }

    private static final class ImageImportStats {
        private final String type;
        private int scanned;
        private int updated;
        private int skipped;
        private int failed;
        private int noMatch;

        private ImageImportStats(String type) {
            this.type = type;
        }

        private static ImageImportStats skipped(String type) {
            ImageImportStats stats = new ImageImportStats(type);
            stats.skipped = 0;
            return stats;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("scanned", scanned);
            map.put("updated", updated);
            map.put("skipped", skipped);
            map.put("failed", failed);
            map.put("noMatch", noMatch);
            return map;
        }
    }
}

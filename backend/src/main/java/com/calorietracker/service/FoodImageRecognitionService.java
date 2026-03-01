package com.calorietracker.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.calorietracker.dto.DishResponse;
import com.calorietracker.dto.ImageRecognitionCandidateResponse;
import com.calorietracker.dto.ImageRecognitionResponse;
import com.calorietracker.dto.IngredientResponse;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FoodImageRecognitionService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final int MAX_HINT_TERMS = 14;
    private static final int MIN_SEARCH_LIMIT = 4;
    private static final int MAX_SEARCH_LIMIT = 20;
    private static final int VISION_MAX_DIMENSION = 1024;
    private static final Set<String> STOPWORDS = Set.of(
        "img", "image", "photo", "camera", "scan", "screenshot", "dcim", "jpg", "jpeg", "png", "heic", "webp", "mov"
    );
    private static final List<String> DEFAULT_LABEL_HINTS = List.of(
        "rice", "biryani", "dosa", "idli", "thali", "chicken noodles", "chilli chicken",
        "salad", "soup", "coffee", "tea", "juice", "fruit", "egg", "fish", "cake"
    );

    private final IngredientService ingredientService;
    private final DishService dishService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.ai.vision.provider:hybrid-local}")
    private String visionProvider;

    @Value("${app.ai.vision.remote-endpoint:}")
    private String remoteEndpoint;

    @Value("${app.ai.vision.remote-key:}")
    private String remoteApiKey;

    @Value("${app.ai.vision.remote-timeout-ms:8000}")
    private int remoteTimeoutMs;

    @Value("${app.ai.vision.openai.base-url:https://api.openai.com/v1/chat/completions}")
    private String openAiBaseUrl;

    @Value("${app.ai.vision.openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${app.ai.vision.openai.key:}")
    private String openAiApiKey;

    @Value("${app.ai.vision.openai.max-labels:8}")
    private int openAiMaxLabels;

    @Value("${app.ai.vision.max-image-bytes:6291456}")
    private long maxImageBytes;

    @Value("${app.ai.vision.max-candidates:10}")
    private int defaultMaxCandidates;

    public FoodImageRecognitionService(
        IngredientService ingredientService,
        DishService dishService,
        ObjectMapper objectMapper
    ) {
        this.ingredientService = ingredientService;
        this.dishService = dishService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public ImageRecognitionResponse recognize(MultipartFile image, String rawHint, Integer limit) {
        ImageRecognitionResponse response = new ImageRecognitionResponse();
        response.setFound(false);
        response.setEngine("HYBRID_LOCAL");

        if (image == null || image.isEmpty()) {
            response.setMessage("Upload a food image to continue.");
            return response;
        }
        if (!isImageContentType(image.getContentType())) {
            response.setMessage("Unsupported file type. Upload JPG, PNG, WEBP, or HEIC image.");
            return response;
        }
        if (image.getSize() <= 0 || image.getSize() > maxImageBytes) {
            response.setMessage("Image is too large. Keep image size under " + (maxImageBytes / (1024 * 1024)) + " MB.");
            return response;
        }

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (IOException exception) {
            response.setMessage("Unable to read image right now.");
            return response;
        }

        int maxCandidates = resolveLimit(limit);
        ImageProfile profile = extractImageProfile(imageBytes);
        Set<String> hintTerms = buildHintTerms(rawHint, image.getOriginalFilename(), profile);
        String normalizedProvider = normalize(visionProvider);

        ExternalLabelResult externalLabels = fetchExternalLabels(
            normalizedProvider,
            imageBytes,
            image.getContentType(),
            rawHint
        );
        List<LabelScore> remoteLabels = externalLabels.labels();
        if (!remoteLabels.isEmpty()) {
            if ("openai".equals(externalLabels.source())) {
                response.setEngine("HYBRID_OPENAI");
            } else if ("remote".equals(externalLabels.source())) {
                response.setEngine("HYBRID_REMOTE");
            }
            hintTerms.addAll(
                remoteLabels.stream()
                    .map(LabelScore::label)
                    .filter(StringUtils::hasText)
                    .map(this::normalize)
                    .filter(StringUtils::hasText)
                    .limit(6)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
            );
        }

        Map<String, ScoredCandidate> merged = new LinkedHashMap<>();
        for (String hint : hintTerms) {
            if (!StringUtils.hasText(hint)) {
                continue;
            }
            collectIngredientCandidates(hint, profile, remoteLabels, merged, maxCandidates);
            collectDishCandidates(hint, profile, remoteLabels, merged, maxCandidates);
            if (merged.size() >= maxCandidates * 3) {
                break;
            }
        }

        if (merged.isEmpty()) {
            for (String fallbackHint : DEFAULT_LABEL_HINTS) {
                collectIngredientCandidates(fallbackHint, profile, remoteLabels, merged, maxCandidates);
                collectDishCandidates(fallbackHint, profile, remoteLabels, merged, maxCandidates);
                if (merged.size() >= maxCandidates * 2) {
                    break;
                }
            }
        }

        List<ImageRecognitionCandidateResponse> ranked = merged.values().stream()
            .sorted((left, right) -> Double.compare(right.score(), left.score()))
            .limit(maxCandidates)
            .map(ScoredCandidate::candidate)
            .collect(Collectors.toList());

        if (ranked.isEmpty()) {
            response.setMessage("Could not confidently recognize this image yet. Try better lighting or add a hint.");
            return response;
        }

        ImageRecognitionCandidateResponse best = ranked.get(0);
        response.setFound(true);
        response.setCandidates(ranked);
        response.setBestType(best.getType());
        response.setBestId(best.getId());
        response.setBestName(best.getName());
        response.setBestConfidence(best.getConfidence());
        response.setHintUsed(hintTerms.stream().limit(4).collect(Collectors.joining(", ")));
        response.setMessage(
            "AI match ready. Review top candidates and confirm before logging."
        );
        return response;
    }

    private void collectIngredientCandidates(
        String hint,
        ImageProfile profile,
        List<LabelScore> remoteLabels,
        Map<String, ScoredCandidate> merged,
        int maxCandidates
    ) {
        List<IngredientResponse> matches;
        try {
            matches = ingredientService.findIngredients(hint, null, null, Math.min(MAX_SEARCH_LIMIT, Math.max(MIN_SEARCH_LIMIT, maxCandidates)));
        } catch (Exception ignored) {
            return;
        }

        for (IngredientResponse item : matches) {
            if (item.getId() == null || !StringUtils.hasText(item.getName())) {
                continue;
            }
            double score = scoreIngredient(item, hint, profile, remoteLabels);
            if (score <= 0) {
                continue;
            }
            ImageRecognitionCandidateResponse candidate = new ImageRecognitionCandidateResponse();
            candidate.setType("ingredient");
            candidate.setId(item.getId());
            candidate.setName(item.getName());
            candidate.setCategory(item.getCategory() == null ? null : item.getCategory().name());
            candidate.setCuisine(item.getCuisine());
            candidate.setImageUrl(item.getImageUrl());
            candidate.setCalories(item.getCaloriesPer100g());
            candidate.setProtein(item.getProteinPer100g());
            candidate.setCarbs(item.getCarbsPer100g());
            candidate.setFats(item.getFatsPer100g());
            candidate.setFiber(item.getFiberPer100g());
            candidate.setConfidence(roundScore(score));
            upsertCandidate("ingredient:" + item.getId(), candidate, score, merged);
        }
    }

    private void collectDishCandidates(
        String hint,
        ImageProfile profile,
        List<LabelScore> remoteLabels,
        Map<String, ScoredCandidate> merged,
        int maxCandidates
    ) {
        List<DishResponse> matches;
        try {
            matches = dishService.findDishSuggestions(hint, Math.min(MAX_SEARCH_LIMIT, Math.max(MIN_SEARCH_LIMIT, maxCandidates)));
        } catch (Exception ignored) {
            return;
        }

        for (DishResponse item : matches) {
            if (item.getId() == null || !StringUtils.hasText(item.getName())) {
                continue;
            }
            double score = scoreDish(item, hint, profile, remoteLabels);
            if (score <= 0) {
                continue;
            }
            ImageRecognitionCandidateResponse candidate = new ImageRecognitionCandidateResponse();
            candidate.setType("dish");
            candidate.setId(item.getId());
            candidate.setName(item.getName());
            candidate.setCuisine(item.getCuisine());
            candidate.setDescription(item.getDescription());
            candidate.setImageUrl(item.getImageUrl());
            candidate.setCalories(item.getCaloriesPerServing());
            candidate.setProtein(item.getProteinPerServing());
            candidate.setCarbs(item.getCarbsPerServing());
            candidate.setFats(item.getFatsPerServing());
            candidate.setFiber(item.getFiberPerServing());
            candidate.setConfidence(roundScore(score));
            upsertCandidate("dish:" + item.getId(), candidate, score, merged);
        }
    }

    private Set<String> buildHintTerms(String rawHint, String fileName, ImageProfile profile) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        appendHintTokens(terms, rawHint);
        appendHintTokens(terms, fileName);
        boolean hasExplicitHints = !terms.isEmpty();

        if (!hasExplicitHints && profile.valid()) {
            if (profile.greenRatio() > 0.39) {
                appendHintTokens(terms, "salad vegetable spinach soup");
            }
            if (profile.warmRatio() > 0.52) {
                appendHintTokens(terms, "curry biryani noodles chicken");
            }
            if (profile.brightness() > 0.68) {
                appendHintTokens(terms, "rice idli milk yogurt");
            }
            if (profile.brightness() < 0.34) {
                appendHintTokens(terms, "coffee chocolate grilled cake");
            }
        }

        if (terms.isEmpty()) {
            terms.addAll(DEFAULT_LABEL_HINTS);
        }

        return terms.stream().limit(MAX_HINT_TERMS).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void appendHintTokens(Set<String> target, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return;
        }

        if (normalized.contains(" ")) {
            target.add(normalized);
            if (target.size() >= MAX_HINT_TERMS) {
                return;
            }
        }

        Matcher matcher = WORD_PATTERN.matcher(normalized);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 3 || STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }

        for (String token : tokens) {
            target.add(token);
            if (target.size() >= MAX_HINT_TERMS) {
                return;
            }
        }

        if (tokens.size() >= 2) {
            target.add(tokens.get(0) + " " + tokens.get(1));
        }
        if (tokens.size() >= 3) {
            target.add(tokens.get(0) + " " + tokens.get(1) + " " + tokens.get(2));
        }
    }

    private ImageProfile extractImageProfile(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return ImageProfile.invalid();
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int step = Math.max(1, Math.min(width, height) / 80);
            long samples = 0;
            double sumR = 0;
            double sumG = 0;
            double sumB = 0;
            double sumBrightness = 0;
            double sumSaturation = 0;
            double warmCount = 0;
            double greenCount = 0;

            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    float[] hsb = Color.RGBtoHSB(r, g, b, null);

                    sumR += r;
                    sumG += g;
                    sumB += b;
                    sumBrightness += hsb[2];
                    sumSaturation += hsb[1];
                    samples++;

                    if (r > g && g >= b && hsb[1] > 0.22) {
                        warmCount++;
                    }
                    if (g >= r && g >= b && hsb[1] > 0.18) {
                        greenCount++;
                    }
                }
            }

            if (samples == 0) {
                return ImageProfile.invalid();
            }

            return new ImageProfile(
                true,
                sumR / samples / 255.0,
                sumG / samples / 255.0,
                sumB / samples / 255.0,
                sumBrightness / samples,
                sumSaturation / samples,
                warmCount / samples,
                greenCount / samples
            );
        } catch (IOException exception) {
            return ImageProfile.invalid();
        }
    }

    private ExternalLabelResult fetchExternalLabels(
        String provider,
        byte[] bytes,
        String contentType,
        String hint
    ) {
        String mode = normalize(provider);
        boolean allowOpenAi = mode.contains("openai");
        boolean allowRemote = mode.contains("remote");
        boolean allowHybrid = mode.contains("hybrid");

        if (!allowOpenAi && !allowRemote && !allowHybrid) {
            return ExternalLabelResult.none();
        }

        if (allowOpenAi || allowHybrid) {
            List<LabelScore> openAiLabels = fetchOpenAiLabels(bytes, contentType, hint);
            if (!openAiLabels.isEmpty()) {
                return new ExternalLabelResult(openAiLabels, "openai");
            }
        }

        if (allowRemote || allowHybrid) {
            List<LabelScore> remoteLabels = fetchCustomRemoteLabels(bytes, hint);
            if (!remoteLabels.isEmpty()) {
                return new ExternalLabelResult(remoteLabels, "remote");
            }
        }
        return ExternalLabelResult.none();
    }

    private List<LabelScore> fetchOpenAiLabels(byte[] bytes, String contentType, String hint) {
        if (!StringUtils.hasText(openAiApiKey)) {
            return List.of();
        }

        try {
            String dataUrl = toVisionDataUrl(bytes, contentType);
            int maxLabels = Math.max(3, Math.min(16, openAiMaxLabels));

            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", StringUtils.hasText(openAiModel) ? openAiModel.trim() : "gpt-4o-mini");
            root.put("temperature", 0.1);
            root.put("max_tokens", 220);

            ArrayNode messages = root.putArray("messages");
            messages.addObject()
                .put("role", "system")
                .put(
                    "content",
                    "Identify food in the image. Return only JSON with key labels. "
                        + "Each item: {\"name\":\"food name\",\"confidence\":0..1}. "
                        + "Prefer dish/ingredient names users search for."
                );

            ObjectNode user = messages.addObject().put("role", "user");
            ArrayNode userContent = user.putArray("content");
            userContent.addObject()
                .put("type", "text")
                .put(
                    "text",
                    "Hint: " + (StringUtils.hasText(hint) ? hint.trim() : "none")
                        + ". Return top " + maxLabels + " labels in JSON."
                );
            ObjectNode imageNode = userContent.addObject();
            imageNode.put("type", "image_url");
            imageNode.putObject("image_url")
                .put("url", dataUrl)
                .put("detail", "low");

            HttpRequest request = HttpRequest.newBuilder(URI.create(openAiBaseUrl.trim()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiApiKey.trim())
                .timeout(Duration.ofMillis(Math.max(1500, remoteTimeoutMs)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400 || !StringUtils.hasText(response.body())) {
                return List.of();
            }

            JsonNode modelRoot = objectMapper.readTree(response.body());
            JsonNode choices = modelRoot.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return List.of();
            }

            JsonNode contentNode = choices.get(0).path("message").path("content");
            StringBuilder contentText = new StringBuilder();
            if (contentNode.isTextual()) {
                contentText.append(contentNode.asText(""));
            } else if (contentNode.isArray()) {
                for (JsonNode node : contentNode) {
                    String text = node.path("text").asText("");
                    if (StringUtils.hasText(text)) {
                        if (contentText.length() > 0) {
                            contentText.append('\n');
                        }
                        contentText.append(text);
                    }
                }
            }

            return parseModelLabels(contentText.toString()).stream()
                .limit(maxLabels)
                .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<LabelScore> fetchCustomRemoteLabels(byte[] bytes, String hint) {
        if (!StringUtils.hasText(remoteEndpoint)) {
            return List.of();
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(bytes);
            JsonNode payload = objectMapper.createObjectNode()
                .put("imageBase64", base64)
                .put("hint", StringUtils.hasText(hint) ? hint.trim() : "")
                .set("labels", objectMapper.valueToTree(DEFAULT_LABEL_HINTS));

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(remoteEndpoint.trim()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(Math.max(1200, remoteTimeoutMs)));

            if (StringUtils.hasText(remoteApiKey)) {
                builder.header("Authorization", "Bearer " + remoteApiKey.trim());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400 || !StringUtils.hasText(response.body())) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode labelsNode = root.path("labels");
            if (!labelsNode.isArray()) {
                labelsNode = root.path("predictions");
            }
            if (!labelsNode.isArray()) {
                return List.of();
            }

            List<LabelScore> labels = new ArrayList<>();
            for (JsonNode node : labelsNode) {
                String label = node.path("name").asText("");
                if (!StringUtils.hasText(label)) {
                    label = node.path("label").asText("");
                }
                if (!StringUtils.hasText(label)) {
                    continue;
                }
                double score = node.path("score").asDouble(0.0);
                if (score <= 0) {
                    score = node.path("confidence").asDouble(0.0);
                }
                if (score <= 0) {
                    score = 0.65;
                }
                labels.add(new LabelScore(normalize(label), Math.max(0, Math.min(1.0, score))));
            }
            return labels.stream()
                .limit(Math.max(3, Math.min(16, openAiMaxLabels)))
                .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<LabelScore> parseModelLabels(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }

        String cleaned = raw.trim()
            .replaceAll("(?s)^```(?:json)?\\s*", "")
            .replaceAll("(?s)\\s*```$", "")
            .trim();

        List<LabelScore> fromJson = parseLabelsFromJson(cleaned);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }

        String[] parts = cleaned.split("[,\\n;]+");
        if (parts.length == 0) {
            return List.of();
        }

        int maxLabels = Math.max(3, Math.min(16, openAiMaxLabels));
        LinkedHashMap<String, Double> unique = new LinkedHashMap<>();
        for (int index = 0; index < parts.length && unique.size() < maxLabels; index++) {
            String candidate = normalize(parts[index].replaceAll("^[\\-\\d\\.\\)\\s]+", ""));
            if (!StringUtils.hasText(candidate) || candidate.length() < 2) {
                continue;
            }
            double score = Math.max(0.4, 0.95 - (index * 0.08));
            unique.putIfAbsent(candidate, score);
        }

        return unique.entrySet().stream()
            .map(entry -> new LabelScore(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }

    private List<LabelScore> parseLabelsFromJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode labelsNode = root;
            if (root.isObject()) {
                labelsNode = root.path("labels");
                if (!labelsNode.isArray()) {
                    labelsNode = root.path("predictions");
                }
                if (!labelsNode.isArray()) {
                    labelsNode = root.path("items");
                }
            }
            if (!labelsNode.isArray()) {
                return List.of();
            }

            int maxLabels = Math.max(3, Math.min(16, openAiMaxLabels));
            LinkedHashMap<String, Double> unique = new LinkedHashMap<>();
            for (JsonNode node : labelsNode) {
                String name;
                double score;
                if (node.isTextual()) {
                    name = normalize(node.asText(""));
                    score = 0.72;
                } else {
                    name = normalize(node.path("name").asText(""));
                    if (!StringUtils.hasText(name)) {
                        name = normalize(node.path("label").asText(""));
                    }
                    if (!StringUtils.hasText(name)) {
                        name = normalize(node.path("item").asText(""));
                    }
                    score = node.path("confidence").asDouble(0.0);
                    if (score <= 0) {
                        score = node.path("score").asDouble(0.0);
                    }
                    if (score <= 0) {
                        score = 0.72;
                    }
                }
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                unique.putIfAbsent(name, Math.max(0.0, Math.min(1.0, score)));
                if (unique.size() >= maxLabels) {
                    break;
                }
            }
            return unique.entrySet().stream()
                .map(entry -> new LabelScore(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String toVisionDataUrl(byte[] bytes, String contentType) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        String mimeType = "image/jpeg";

        if (image == null) {
            String fallbackType = StringUtils.hasText(contentType) ? contentType.trim().toLowerCase(Locale.ROOT) : "";
            if (fallbackType.startsWith("image/")) {
                mimeType = fallbackType;
            }
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        double scale = 1.0;
        int maxDimension = Math.max(width, height);
        if (maxDimension > VISION_MAX_DIMENSION) {
            scale = VISION_MAX_DIMENSION / (double) maxDimension;
        }

        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean encoded = ImageIO.write(resized, "jpg", output);
        if (encoded) {
            mimeType = "image/jpeg";
        } else {
            output.reset();
            ImageIO.write(resized, "png", output);
            mimeType = "image/png";
        }

        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private double scoreIngredient(
        IngredientResponse ingredient,
        String hint,
        ImageProfile profile,
        List<LabelScore> remoteLabels
    ) {
        String name = normalize(ingredient.getName());
        String query = normalize(hint);
        double textScore = similarity(name, query);
        double overlapScore = tokenOverlapScore(name, query);
        double queryBoost = querySpecificityBoost(name, query);

        if (ingredient.getAliases() != null) {
            for (String alias : ingredient.getAliases()) {
                textScore = Math.max(textScore, similarity(normalize(alias), query) * 0.97);
                overlapScore = Math.max(overlapScore, tokenOverlapScore(normalize(alias), query));
                queryBoost = Math.max(queryBoost, querySpecificityBoost(normalize(alias), query) * 0.95);
            }
        }
        textScore = Math.max(textScore, similarity(normalize(ingredient.getCuisine()), query) * 0.7);
        textScore = Math.max(textScore, similarity(normalize(ingredient.getCategory() == null ? "" : ingredient.getCategory().name()), query) * 0.65);

        double visualBoost = visualBoost(name + " " + normalize(ingredient.getCuisine()), profile);
        double remoteBoost = remoteLabelBoost(name, remoteLabels);
        double score = textScore * 0.46 + overlapScore * 0.22 + visualBoost * 0.14 + remoteBoost * 0.08 + queryBoost * 0.1;

        if (Boolean.TRUE.equals(ingredient.getFactChecked())) {
            score += 0.035;
        }
        return Math.max(0, Math.min(1.0, score));
    }

    private double scoreDish(
        DishResponse dish,
        String hint,
        ImageProfile profile,
        List<LabelScore> remoteLabels
    ) {
        String name = normalize(dish.getName());
        String query = normalize(hint);
        double textScore = similarity(name, query);
        double overlapScore = tokenOverlapScore(name, query);
        double queryBoost = querySpecificityBoost(name, query);
        textScore = Math.max(textScore, similarity(normalize(dish.getCuisine()), query) * 0.74);
        textScore = Math.max(textScore, similarity(normalize(dish.getDescription()), query) * 0.7);
        overlapScore = Math.max(overlapScore, tokenOverlapScore(normalize(dish.getDescription()), query) * 0.9);
        queryBoost = Math.max(queryBoost, querySpecificityBoost(normalize(dish.getDescription()), query) * 0.8);

        double visualBoost = visualBoost(name + " " + normalize(dish.getDescription()) + " " + normalize(dish.getCuisine()), profile);
        double remoteBoost = remoteLabelBoost(name, remoteLabels);
        double score = textScore * 0.42 + overlapScore * 0.22 + visualBoost * 0.14 + remoteBoost * 0.08 + queryBoost * 0.14;

        if (Boolean.TRUE.equals(dish.getFactChecked())) {
            score += 0.03;
        }
        return Math.max(0, Math.min(1.0, score));
    }

    private double visualBoost(String candidateText, ImageProfile profile) {
        if (!profile.valid()) {
            return 0.4;
        }

        String text = normalize(candidateText);
        double score = 0.36;

        if (profile.greenRatio() > 0.39 && containsAny(text, "salad", "spinach", "vegetable", "veg", "greens", "broccoli")) {
            score += 0.34;
        }
        if (profile.warmRatio() > 0.5 && containsAny(text, "curry", "biryani", "fried", "chicken", "noodles", "masala", "gravy")) {
            score += 0.33;
        }
        if (profile.brightness() > 0.68 && containsAny(text, "rice", "idli", "milk", "curd", "yogurt", "paneer")) {
            score += 0.29;
        }
        if (profile.brightness() < 0.34 && containsAny(text, "coffee", "chocolate", "cake", "brownie", "roast", "grill")) {
            score += 0.29;
        }
        if (profile.saturation() > 0.5 && containsAny(text, "fruit", "juice", "smoothie", "orange", "mango", "berry")) {
            score += 0.24;
        }

        if (profile.red() > 0.55 && profile.green() < 0.4 && containsAny(text, "tomato", "ketchup", "chilli", "sauce")) {
            score += 0.18;
        }

        return Math.max(0, Math.min(1.0, score));
    }

    private double remoteLabelBoost(String candidateName, List<LabelScore> remoteLabels) {
        if (remoteLabels == null || remoteLabels.isEmpty()) {
            return 0.0;
        }
        String normalizedCandidate = normalize(candidateName);
        double best = 0.0;
        for (LabelScore labelScore : remoteLabels) {
            double similarity = similarity(normalizedCandidate, normalize(labelScore.label()));
            best = Math.max(best, similarity * labelScore.score());
        }
        return Math.max(0.0, Math.min(1.0, best));
    }

    private void upsertCandidate(
        String key,
        ImageRecognitionCandidateResponse candidate,
        double score,
        Map<String, ScoredCandidate> merged
    ) {
        ScoredCandidate existing = merged.get(key);
        if (existing == null || score > existing.score()) {
            candidate.setConfidence(roundScore(score));
            merged.put(key, new ScoredCandidate(candidate, score));
        }
    }

    private int resolveLimit(Integer limit) {
        int fallback = Math.max(6, defaultMaxCandidates);
        if (limit == null || limit <= 0) {
            return Math.min(MAX_SEARCH_LIMIT, fallback);
        }
        return Math.min(MAX_SEARCH_LIMIT, Math.max(MIN_SEARCH_LIMIT, limit));
    }

    private boolean isImageContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        return contentType.toLowerCase(Locale.ROOT).startsWith("image/");
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

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private double similarity(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return 0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        if (left.startsWith(right) || right.startsWith(left)) {
            return 0.93;
        }
        if (left.contains(right) || right.contains(left)) {
            return 0.82;
        }

        String[] leftTokens = left.split(" ");
        String[] rightTokens = right.split(" ");
        double bestToken = 0.0;
        for (String leftToken : leftTokens) {
            for (String rightToken : rightTokens) {
                if (!StringUtils.hasText(leftToken) || !StringUtils.hasText(rightToken)) {
                    continue;
                }
                int distance = levenshtein(leftToken, rightToken);
                int max = Math.max(leftToken.length(), rightToken.length());
                if (max == 0) {
                    continue;
                }
                double ratio = 1.0 - (distance / (double) max);
                if (ratio > bestToken) {
                    bestToken = ratio;
                }
            }
        }
        return Math.max(0, Math.min(1.0, bestToken));
    }

    private double tokenOverlapScore(String text, String query) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(query)) {
            return 0.0;
        }
        Set<String> textTokens = tokenize(text);
        Set<String> queryTokens = tokenize(query);
        if (textTokens.isEmpty() || queryTokens.isEmpty()) {
            return 0.0;
        }
        long matched = queryTokens.stream().filter(textTokens::contains).count();
        return matched / (double) queryTokens.size();
    }

    private double querySpecificityBoost(String candidateText, String query) {
        if (!StringUtils.hasText(candidateText) || !StringUtils.hasText(query)) {
            return 0.0;
        }

        String candidate = normalize(candidateText);
        String normalizedQuery = normalize(query);
        if (!StringUtils.hasText(candidate) || !StringUtils.hasText(normalizedQuery)) {
            return 0.0;
        }

        Set<String> candidateTokens = tokenize(candidate);
        Set<String> queryTokens = tokenize(normalizedQuery);
        if (candidateTokens.isEmpty() || queryTokens.isEmpty()) {
            return 0.0;
        }

        long matched = queryTokens.stream().filter(candidateTokens::contains).count();
        if (matched <= 0) {
            return 0.0;
        }

        if (queryTokens.size() == 1) {
            if (candidate.equals(normalizedQuery)) {
                return 1.0;
            }
            if (candidate.startsWith(normalizedQuery + " ") || candidate.contains(" " + normalizedQuery + " ")) {
                return 0.55;
            }
            return 0.32;
        }

        double coverage = matched / (double) queryTokens.size();
        if (coverage >= 1.0) {
            if (candidate.equals(normalizedQuery)) {
                return 1.0;
            }
            if (candidate.startsWith(normalizedQuery) || candidate.contains(" " + normalizedQuery + " ")) {
                return 0.95;
            }
            return 0.88;
        }
        if (coverage >= 0.75) {
            return 0.52;
        }
        if (coverage >= 0.5) {
            return 0.28;
        }
        return 0.12;
    }

    private Set<String> tokenize(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = WORD_PATTERN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int levenshtein(String left, String right) {
        int leftLen = left.length();
        int rightLen = right.length();
        int[] previous = new int[rightLen + 1];
        int[] current = new int[rightLen + 1];
        for (int j = 0; j <= rightLen; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftLen; i++) {
            current[0] = i;
            for (int j = 1; j <= rightLen; j++) {
                int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitutionCost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[rightLen];
    }

    private double roundScore(double value) {
        return Math.round(Math.max(0, Math.min(1.0, value)) * 1000.0) / 1000.0;
    }

    private record LabelScore(String label, double score) {
    }

    private record ExternalLabelResult(List<LabelScore> labels, String source) {
        private static ExternalLabelResult none() {
            return new ExternalLabelResult(List.of(), "none");
        }
    }

    private record ScoredCandidate(ImageRecognitionCandidateResponse candidate, double score) {
    }

    private record ImageProfile(
        boolean valid,
        double red,
        double green,
        double blue,
        double brightness,
        double saturation,
        double warmRatio,
        double greenRatio
    ) {
        private static ImageProfile invalid() {
            return new ImageProfile(false, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}

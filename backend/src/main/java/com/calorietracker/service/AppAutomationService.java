package com.calorietracker.service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.calorietracker.controller.ImportController;
import com.calorietracker.entity.AutomationTaskState;
import com.calorietracker.repository.AutomationTaskStateRepository;

@Service
public class AppAutomationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppAutomationService.class);

    private static final String TASK_CORRECTION = "dataset-correction";
    private static final String TASK_OPEN_FOOD_FACTS = "open-food-facts";
    private static final String TASK_WORLD_CUISINES = "world-cuisines";
    private static final String TASK_SWEETS = "sweets-desserts";
    private static final String TASK_IMAGES = "images";

    private static final String TYPE_CORRECTION = "DATASET_CORRECTION";
    private static final String TYPE_OPEN_FOOD_FACTS = "OPEN_FOOD_FACTS";
    private static final String TYPE_WORLD_CUISINES = "WORLD_CUISINES";
    private static final String TYPE_SWEETS = "SWEETS_DATASETS";
    private static final String TYPE_IMAGES = "WIKIMEDIA_IMAGES";

    private final ImportController importController;
    private final ImportJobService importJobService;
    private final AutomationTaskStateRepository automationTaskStateRepository;

    @Value("${app.automation.enabled:true}")
    private boolean enabled;

    @Value("${app.automation.poll-ms:900000}")
    private long pollMs;

    @Value("${app.automation.internet.timeout-ms:3500}")
    private int internetTimeoutMs;

    @Value(
        "${app.automation.internet.probes:https://world.openfoodfacts.org,https://www.themealdb.com/api/json/v1/1/list.php?c=list,https://commons.wikimedia.org/wiki/Main_Page}"
    )
    private String internetProbesCsv;

    @Value(
        "${app.automation.default.cuisines:indian,chinese,indo chinese,european,mediterranean,african,western,eastern,northern,southern}"
    )
    private String defaultCuisines;

    @Value(
        "${app.automation.default.countries:india,china,japan,thailand,vietnam,indonesia,philippines,saudi-arabia,turkey,egypt,morocco,nigeria,south-africa,united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia}"
    )
    private String defaultCountries;

    @Value("${app.automation.open-food-facts.pages:2}")
    private int openFoodFactsPages;

    @Value("${app.automation.open-food-facts.page-size:180}")
    private int openFoodFactsPageSize;

    @Value("${app.automation.world.max-per-cuisine:40}")
    private int worldMaxPerCuisine;

    @Value("${app.automation.world.include-open-food-facts:true}")
    private boolean worldIncludeOpenFoodFacts;

    @Value("${app.automation.world.pages:2}")
    private int worldPages;

    @Value("${app.automation.world.page-size:140}")
    private int worldPageSize;

    @Value("${app.automation.sweets.pages:1}")
    private int sweetsPages;

    @Value("${app.automation.sweets.page-size:120}")
    private int sweetsPageSize;

    @Value("${app.automation.sweets.max-per-query:14}")
    private int sweetsMaxPerQuery;

    @Value("${app.automation.sweets.max-mealdb-desserts:140}")
    private int sweetsMaxMealDbDesserts;

    @Value("${app.automation.images.ingredient-limit:280}")
    private int imagesIngredientLimit;

    @Value("${app.automation.images.dish-limit:280}")
    private int imagesDishLimit;

    @Value("${app.automation.task.correction-hours:8}")
    private long correctionHours;

    @Value("${app.automation.task.open-food-facts-hours:12}")
    private long openFoodFactsHours;

    @Value("${app.automation.task.world-cuisines-hours:24}")
    private long worldCuisinesHours;

    @Value("${app.automation.task.sweets-hours:24}")
    private long sweetsHours;

    @Value("${app.automation.task.images-hours:72}")
    private long imagesHours;

    private volatile Instant lastCycleAt;
    private volatile String lastCycleMessage = "Automation has not run yet.";
    private volatile boolean lastInternetReachable = false;

    public AppAutomationService(
        ImportController importController,
        ImportJobService importJobService,
        AutomationTaskStateRepository automationTaskStateRepository
    ) {
        this.importController = importController;
        this.importJobService = importJobService;
        this.automationTaskStateRepository = automationTaskStateRepository;
    }

    @Scheduled(
        initialDelayString = "${app.automation.initial-delay-ms:90000}",
        fixedDelayString = "${app.automation.poll-ms:900000}"
    )
    public synchronized void scheduledRefreshCycle() {
        runCycle("scheduled");
    }

    public synchronized Map<String, Object> triggerNow(String requestedTaskKey) {
        return runCycle(requestedTaskKey == null || requestedTaskKey.isBlank() ? "manual-next" : requestedTaskKey.trim().toLowerCase());
    }

    public Map<String, Object> getStatusSnapshot() {
        syncStatesFromRecentJobs();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", enabled);
        response.put("pollMs", pollMs);
        response.put("lastCycleAt", lastCycleAt == null ? null : lastCycleAt.toString());
        response.put("lastCycleMessage", lastCycleMessage);
        response.put("internetReachable", lastInternetReachable);

        List<Map<String, Object>> taskRows = new ArrayList<>();
        for (TaskDefinition definition : taskDefinitions()) {
            AutomationTaskState state = loadState(definition.taskKey(), definition.label());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskKey", definition.taskKey());
            row.put("label", definition.label());
            row.put("importType", definition.importType());
            row.put("intervalHours", definition.intervalHours());
            row.put("lastStatus", state.getLastStatus());
            row.put("lastMessage", state.getLastMessage());
            row.put("lastAttemptAt", state.getLastAttemptAt() == null ? null : state.getLastAttemptAt().toString());
            row.put("lastSuccessAt", state.getLastSuccessAt() == null ? null : state.getLastSuccessAt().toString());
            row.put("lastFinishedAt", state.getLastFinishedAt() == null ? null : state.getLastFinishedAt().toString());
            taskRows.add(row);
        }
        response.put("tasks", taskRows);
        return response;
    }

    private Map<String, Object> runCycle(String modeOrTask) {
        Instant now = Instant.now();
        lastCycleAt = now;
        boolean isManual = !"scheduled".equals(modeOrTask);

        syncStatesFromRecentJobs();

        if (!enabled && !isManual) {
            lastCycleMessage = "Automation disabled.";
            return Map.of("status", "disabled", "message", lastCycleMessage);
        }

        if (hasRunningImport()) {
            lastCycleMessage = "Skipped. Another import job is already running.";
            return Map.of("status", "skipped", "reason", "import_running", "message", lastCycleMessage);
        }

        lastInternetReachable = isInternetReachable();
        if (!lastInternetReachable) {
            lastCycleMessage = "Skipped. Internet is not reachable for import sources.";
            return Map.of("status", "skipped", "reason", "offline", "message", lastCycleMessage);
        }

        Optional<TaskDefinition> targetTask;
        if ("manual-next".equals(modeOrTask) || "scheduled".equals(modeOrTask)) {
            targetTask = selectNextDueTask(now);
        } else {
            targetTask = taskDefinitions().stream()
                .filter(task -> task.taskKey().equals(modeOrTask))
                .findFirst();
            if (targetTask.isEmpty()) {
                lastCycleMessage = "Unknown task key: " + modeOrTask;
                return Map.of("status", "error", "message", lastCycleMessage);
            }
        }

        if (targetTask.isEmpty()) {
            lastCycleMessage = "No task is due right now.";
            return Map.of("status", "ok", "message", lastCycleMessage, "internetReachable", true);
        }

        TaskDefinition task = targetTask.get();
        try {
            Map<String, Object> trigger = task.trigger().get();
            String jobId = asString(trigger.get("jobId"));

            AutomationTaskState state = loadState(task.taskKey(), task.label());
            state.setLastAttemptAt(now);
            state.setLastStatus("TRIGGERED");
            state.setLastMessage(jobId == null ? "Triggered" : ("Triggered job " + jobId));
            automationTaskStateRepository.save(state);

            lastCycleMessage = "Triggered " + task.taskKey() + (jobId == null ? "" : (" (job " + jobId + ")"));
            LOGGER.info("Automation triggered task={} jobId={}", task.taskKey(), jobId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "triggered");
            response.put("taskKey", task.taskKey());
            response.put("label", task.label());
            response.put("jobId", jobId);
            response.put("cycleMessage", lastCycleMessage);
            return response;
        } catch (Exception ex) {
            AutomationTaskState state = loadState(task.taskKey(), task.label());
            state.setLastAttemptAt(now);
            state.setLastFinishedAt(now);
            state.setLastStatus("TRIGGER_FAILED");
            state.setLastMessage(shortMessage(ex.getMessage()));
            automationTaskStateRepository.save(state);

            lastCycleMessage = "Trigger failed for " + task.taskKey() + ": " + shortMessage(ex.getMessage());
            LOGGER.warn("Automation trigger failed for task={}: {}", task.taskKey(), ex.getMessage());
            return Map.of("status", "failed", "taskKey", task.taskKey(), "message", lastCycleMessage);
        }
    }

    private Optional<TaskDefinition> selectNextDueTask(Instant now) {
        return taskDefinitions().stream()
            .filter(task -> task.intervalHours() > 0)
            .filter(task -> isDue(task, now))
            .max(Comparator.comparing(task -> dueSince(task, now)));
    }

    private Duration dueSince(TaskDefinition task, Instant now) {
        AutomationTaskState state = loadState(task.taskKey(), task.label());
        if (state.getLastSuccessAt() == null) {
            return Duration.of(Long.MAX_VALUE / 4, ChronoUnit.MILLIS);
        }
        Duration elapsed = Duration.between(state.getLastSuccessAt(), now);
        Duration interval = Duration.ofHours(task.intervalHours());
        Duration dueBy = elapsed.minus(interval);
        if (dueBy.isNegative()) {
            return Duration.ZERO;
        }
        return dueBy;
    }

    private boolean isDue(TaskDefinition task, Instant now) {
        AutomationTaskState state = loadState(task.taskKey(), task.label());
        if (state.getLastSuccessAt() == null) {
            return true;
        }
        long elapsedHours = Duration.between(state.getLastSuccessAt(), now).toHours();
        return elapsedHours >= task.intervalHours();
    }

    private void syncStatesFromRecentJobs() {
        List<Map<String, Object>> jobs = importJobService.listJobs(100);
        if (jobs.isEmpty()) {
            return;
        }

        Map<String, Map<String, Object>> latestByType = new LinkedHashMap<>();
        for (Map<String, Object> job : jobs) {
            String type = asString(job.get("type"));
            if (type == null || latestByType.containsKey(type)) {
                continue;
            }
            latestByType.put(type, job);
        }

        for (TaskDefinition task : taskDefinitions()) {
            Map<String, Object> job = latestByType.get(task.importType());
            if (job == null) {
                continue;
            }
            applyJobState(task, job);
        }
    }

    private void applyJobState(TaskDefinition task, Map<String, Object> job) {
        AutomationTaskState state = loadState(task.taskKey(), task.label());
        String jobState = asString(job.get("state"));
        String jobMessage = asString(job.get("message"));
        Instant createdAt = parseInstant(job.get("createdAt"));
        Instant finishedAt = parseInstant(job.get("finishedAt"));

        boolean changed = false;

        if (createdAt != null && (state.getLastAttemptAt() == null || createdAt.isAfter(state.getLastAttemptAt()))) {
            state.setLastAttemptAt(createdAt);
            changed = true;
        }

        if (jobState != null && !jobState.equals(state.getLastStatus())) {
            state.setLastStatus(jobState);
            changed = true;
        }

        if (jobMessage != null && !jobMessage.equals(state.getLastMessage())) {
            state.setLastMessage(shortMessage(jobMessage));
            changed = true;
        }

        if (finishedAt != null && (state.getLastFinishedAt() == null || finishedAt.isAfter(state.getLastFinishedAt()))) {
            state.setLastFinishedAt(finishedAt);
            changed = true;
        }

        if ("COMPLETED".equals(jobState) && finishedAt != null
            && (state.getLastSuccessAt() == null || finishedAt.isAfter(state.getLastSuccessAt()))) {
            state.setLastSuccessAt(finishedAt);
            changed = true;
        }

        if (changed) {
            automationTaskStateRepository.save(state);
        }
    }

    private boolean hasRunningImport() {
        return importJobService.listJobs(40).stream()
            .map(row -> asString(row.get("state")))
            .anyMatch(state -> "RUNNING".equals(state) || "QUEUED".equals(state));
    }

    private boolean isInternetReachable() {
        for (String probe : parseCsv(internetProbesCsv)) {
            if (isProbeReachable(probe)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProbeReachable(String probe) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(probe);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.max(1200, internetTimeoutMs));
            connection.setReadTimeout(Math.max(1200, internetTimeoutMs));
            connection.setRequestProperty("User-Agent", "calorie-tracker-auto-refresh/1.0");
            int code = connection.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception ex) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] split = csv.split(",");
        List<String> result = new ArrayList<>();
        for (String item : split) {
            String normalized = item == null ? "" : item.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private List<TaskDefinition> taskDefinitions() {
        List<TaskDefinition> tasks = new ArrayList<>();
        tasks.add(
            new TaskDefinition(
                TASK_CORRECTION,
                "Dataset correction and alias cleanup",
                TYPE_CORRECTION,
                Math.max(0, correctionHours),
                () -> importController.correctDatasetsAsync(true)
            )
        );
        tasks.add(
            new TaskDefinition(
                TASK_OPEN_FOOD_FACTS,
                "Open Food Facts refresh",
                TYPE_OPEN_FOOD_FACTS,
                Math.max(0, openFoodFactsHours),
                () -> importController.importOpenFoodFactsAsync(
                    defaultCountries,
                    Math.max(1, openFoodFactsPages),
                    Math.max(40, openFoodFactsPageSize)
                )
            )
        );
        tasks.add(
            new TaskDefinition(
                TASK_WORLD_CUISINES,
                "World cuisine dishes refresh",
                TYPE_WORLD_CUISINES,
                Math.max(0, worldCuisinesHours),
                () -> importController.importWorldCuisinesAsync(
                    defaultCuisines,
                    Math.max(8, worldMaxPerCuisine),
                    worldIncludeOpenFoodFacts,
                    defaultCountries,
                    Math.max(1, worldPages),
                    Math.max(60, worldPageSize)
                )
            )
        );
        tasks.add(
            new TaskDefinition(
                TASK_SWEETS,
                "Sweets and desserts refresh",
                TYPE_SWEETS,
                Math.max(0, sweetsHours),
                () -> importController.importSweetsDessertsAsync(
                    defaultCountries,
                    Math.max(1, sweetsPages),
                    Math.max(60, sweetsPageSize),
                    Math.max(5, sweetsMaxPerQuery),
                    Math.max(40, sweetsMaxMealDbDesserts),
                    true
                )
            )
        );
        tasks.add(
            new TaskDefinition(
                TASK_IMAGES,
                "Food image enrichment",
                TYPE_IMAGES,
                Math.max(0, imagesHours),
                () -> importController.importImagesAsync(
                    true,
                    true,
                    Math.max(80, imagesIngredientLimit),
                    Math.max(80, imagesDishLimit),
                    false
                )
            )
        );
        return tasks;
    }

    private AutomationTaskState loadState(String taskKey, String taskLabel) {
        Optional<AutomationTaskState> existing = automationTaskStateRepository.findFirstByTaskKey(taskKey);
        if (existing.isPresent()) {
            AutomationTaskState state = existing.get();
            if (!taskLabel.equals(state.getTaskLabel())) {
                state.setTaskLabel(taskLabel);
                return automationTaskStateRepository.save(state);
            }
            return state;
        }

        AutomationTaskState created = new AutomationTaskState();
        created.setTaskKey(taskKey);
        created.setTaskLabel(taskLabel);
        created.setLastStatus("NEVER_RUN");
        created.setLastMessage("Waiting for first cycle.");
        try {
            return automationTaskStateRepository.save(created);
        } catch (DataIntegrityViolationException ex) {
            return automationTaskStateRepository.findFirstByTaskKey(taskKey).orElseThrow(() -> ex);
        }
    }

    private Instant parseInstant(Object value) {
        if (!(value instanceof String stringValue)) {
            return null;
        }
        if (stringValue.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(stringValue);
        } catch (Exception ex) {
            return null;
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString();
        return raw.isBlank() ? null : raw;
    }

    private String shortMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.length() <= 480) {
            return trimmed;
        }
        return trimmed.substring(0, 480);
    }

    private record TaskDefinition(
        String taskKey,
        String label,
        String importType,
        long intervalHours,
        Supplier<Map<String, Object>> trigger
    ) {
    }
}

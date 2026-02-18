package com.calorietracker.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ImportJobService {

    private static final int MAX_JOBS_TO_KEEP = 150;

    private final ConcurrentHashMap<String, ImportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("import-job-runner");
        thread.setDaemon(true);
        return thread;
    });

    public String submit(String type, Supplier<Map<String, Object>> action) {
        String id = UUID.randomUUID().toString();

        ImportJob job = new ImportJob(id, type);
        jobs.put(id, job);
        trimOldJobs();

        executor.submit(() -> runJob(job, action));
        return id;
    }

    public Map<String, Object> getJobOrThrow(String jobId) {
        ImportJob job = jobs.get(jobId);
        if (job == null) {
            throw new ResponseStatusException(NOT_FOUND, "Import job not found: " + jobId);
        }
        return job.toMap();
    }

    public List<Map<String, Object>> listJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jobs.values().stream()
            .sorted(Comparator.comparing(ImportJob::createdAt).reversed())
            .limit(safeLimit)
            .map(ImportJob::toMap)
            .collect(Collectors.toList());
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void runJob(ImportJob job, Supplier<Map<String, Object>> action) {
        job.markRunning();
        try {
            Map<String, Object> result = action.get();
            job.markCompleted(result);
        } catch (Exception ex) {
            job.markFailed(ex.getMessage() == null ? "Import failed." : ex.getMessage());
        }
    }

    private void trimOldJobs() {
        if (jobs.size() <= MAX_JOBS_TO_KEEP) {
            return;
        }

        List<ImportJob> oldest = new ArrayList<>(jobs.values());
        oldest.sort(Comparator.comparing(ImportJob::createdAt));

        int deleteCount = jobs.size() - MAX_JOBS_TO_KEEP;
        for (int i = 0; i < deleteCount; i++) {
            jobs.remove(oldest.get(i).id());
        }
    }

    private enum ImportJobState {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private static final class ImportJob {
        private final String id;
        private final String type;
        private final Instant createdAt;

        private volatile ImportJobState state;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String message;
        private volatile Map<String, Object> result;

        private ImportJob(String id, String type) {
            this.id = id;
            this.type = type;
            this.createdAt = Instant.now();
            this.state = ImportJobState.QUEUED;
            this.message = "Queued";
            this.result = Map.of();
        }

        private synchronized void markRunning() {
            this.state = ImportJobState.RUNNING;
            this.startedAt = Instant.now();
            this.message = "Running";
        }

        private synchronized void markCompleted(Map<String, Object> result) {
            this.state = ImportJobState.COMPLETED;
            this.finishedAt = Instant.now();
            this.message = "Completed";
            this.result = result == null ? Map.of() : Map.copyOf(result);
        }

        private synchronized void markFailed(String message) {
            this.state = ImportJobState.FAILED;
            this.finishedAt = Instant.now();
            this.message = message;
        }

        private String id() {
            return id;
        }

        private Instant createdAt() {
            return createdAt;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", id);
            map.put("type", type);
            map.put("state", state.name());
            map.put("createdAt", createdAt.toString());
            map.put("startedAt", startedAt == null ? null : startedAt.toString());
            map.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            map.put("message", message);
            map.put("result", result);
            return map;
        }
    }
}

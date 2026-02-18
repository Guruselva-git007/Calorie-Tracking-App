package com.calorietracker.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.runtime.db-mode:mysql}")
    private String dbMode;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app", "calorie-tracker");
        payload.put("timestamp", Instant.now().toString());
        payload.put("dbMode", dbMode);

        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            payload.put("status", "UP");
            payload.put("database", "UP");
            return ResponseEntity.ok(payload);
        } catch (Exception ex) {
            payload.put("status", "DEGRADED");
            payload.put("database", "DOWN");
            payload.put("message", "Backend is running but database is unavailable.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(payload);
        }
    }
}

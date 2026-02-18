package com.calorietracker.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.CreateDishEntryRequest;
import com.calorietracker.dto.CreateIngredientEntryRequest;
import com.calorietracker.dto.DaySummaryResponse;
import com.calorietracker.dto.EntryResponse;
import com.calorietracker.dto.LegacyEntryRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.CalorieMath;
import com.calorietracker.service.EntryService;
import com.calorietracker.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/entries")
@Validated
public class EntryController {

    private final EntryService entryService;
    private final UserService userService;

    public EntryController(EntryService entryService, UserService userService) {
        this.entryService = entryService;
        this.userService = userService;
    }

    @GetMapping
    public List<EntryResponse> getEntries(@RequestParam(defaultValue = "1") Long userId) {
        return entryService.getEntries(userId);
    }

    @GetMapping("/today")
    public List<EntryResponse> getToday(@RequestParam(defaultValue = "1") Long userId) {
        return entryService.getEntriesByDate(userId, LocalDate.now());
    }

    @GetMapping("/date/{date}")
    public List<EntryResponse> getByDate(
        @RequestParam(defaultValue = "1") Long userId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return entryService.getEntriesByDate(userId, date);
    }

    @GetMapping("/summary")
    public DaySummaryResponse getSummary(
        @RequestParam(defaultValue = "1") Long userId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        AppUser user = userService.getUserOrThrow(userId);

        List<EntryResponse> entries = entryService.getEntriesByDate(userId, targetDate);
        double totalCalories = entries.stream().mapToDouble(EntryResponse::getTotalCalories).sum();

        DaySummaryResponse response = new DaySummaryResponse();
        response.setDate(targetDate);
        response.setDailyGoal(user.getDailyCalorieGoal());
        response.setTotalCalories(CalorieMath.round(totalCalories));
        response.setRemainingCalories(CalorieMath.round(user.getDailyCalorieGoal() - totalCalories));
        response.setEntryCount(entries.size());
        return response;
    }

    @PostMapping("/ingredient")
    public EntryResponse createIngredientEntry(@Valid @RequestBody CreateIngredientEntryRequest request) {
        return entryService.createIngredientEntry(request);
    }

    @PostMapping("/dish")
    public EntryResponse createDishEntry(@Valid @RequestBody CreateDishEntryRequest request) {
        return entryService.createDishEntry(request);
    }

    @PostMapping
    public EntryResponse createLegacyEntry(@Valid @RequestBody LegacyEntryRequest legacyRequest) {
        CreateIngredientEntryRequest request = new CreateIngredientEntryRequest();
        request.setUserId(legacyRequest.getUserId());
        request.setIngredientId(legacyRequest.getFoodId());
        request.setGrams(legacyRequest.getQuantity() * 100.0);
        return entryService.createIngredientEntry(request);
    }

    @DeleteMapping("/{id}")
    public void deleteEntry(@PathVariable Long id) {
        entryService.deleteEntry(id);
    }
}

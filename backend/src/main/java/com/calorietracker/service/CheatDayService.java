package com.calorietracker.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.calorietracker.dto.CheatDayEntryResponse;
import com.calorietracker.dto.CheatDayUpsertRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.CheatDayRecord;
import com.calorietracker.repository.CheatDayRecordRepository;

@Service
public class CheatDayService {

    private static final int DEFAULT_LIMIT = 24;
    private static final int MAX_LIMIT = 180;

    private final CheatDayRecordRepository cheatDayRecordRepository;
    private final UserService userService;

    public CheatDayService(CheatDayRecordRepository cheatDayRecordRepository, UserService userService) {
        this.cheatDayRecordRepository = cheatDayRecordRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<CheatDayEntryResponse> listByUser(Long userId, Integer limit) {
        AppUser user = userService.getUserOrThrow(userId);
        int safeLimit = sanitizeLimit(limit);
        return cheatDayRecordRepository.findTop180ByUserIdOrderByCheatDateDesc(user.getId())
            .stream()
            .sorted(Comparator.comparing(CheatDayRecord::getCheatDate).reversed())
            .limit(safeLimit)
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public CheatDayEntryResponse upsert(CheatDayUpsertRequest request) {
        AppUser user = userService.getUserOrThrow(request.getUserId());
        LocalDate targetDate = request.getDate();
        CheatDayRecord record = cheatDayRecordRepository
            .findFirstByUserIdAndCheatDate(user.getId(), targetDate)
            .orElseGet(CheatDayRecord::new);

        record.setUser(user);
        record.setCheatDate(targetDate);
        record.setTitle(sanitizeNullableText(request.getTitle(), 120));
        record.setNote(sanitizeNullableText(request.getNote(), 1200));
        record.setIndulgenceLevel(clampNullableInt(request.getIndulgenceLevel(), 1, 5));
        record.setEstimatedExtraCalories(clampNullableInt(request.getEstimatedExtraCalories(), 0, 5000));

        CheatDayRecord saved = cheatDayRecordRepository.save(record);
        return toResponse(saved);
    }

    @Transactional
    public boolean deleteByUserAndDate(Long userId, LocalDate date) {
        AppUser user = userService.getUserOrThrow(userId);
        return cheatDayRecordRepository.findFirstByUserIdAndCheatDate(user.getId(), date)
            .map(record -> {
                cheatDayRecordRepository.delete(record);
                return true;
            })
            .orElse(false);
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Integer clampNullableInt(Integer value, int min, int max) {
        if (value == null) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String sanitizeNullableText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private CheatDayEntryResponse toResponse(CheatDayRecord record) {
        CheatDayEntryResponse response = new CheatDayEntryResponse();
        response.setId(record.getId());
        response.setUserId(record.getUser().getId());
        response.setDate(record.getCheatDate());
        response.setTitle(record.getTitle());
        response.setNote(record.getNote());
        response.setIndulgenceLevel(record.getIndulgenceLevel());
        response.setEstimatedExtraCalories(record.getEstimatedExtraCalories());
        response.setCreatedAt(record.getCreatedAt());
        response.setUpdatedAt(record.getUpdatedAt());
        return response;
    }
}

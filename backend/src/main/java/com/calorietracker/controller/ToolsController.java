package com.calorietracker.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import com.calorietracker.dto.CheatDayEntryResponse;
import com.calorietracker.dto.CheatDayUpsertRequest;
import com.calorietracker.dto.BarcodeLookupResponse;
import com.calorietracker.dto.DeficiencyToolsRequest;
import com.calorietracker.dto.DeficiencyToolsResponse;
import com.calorietracker.dto.ImageRecognitionResponse;
import com.calorietracker.service.BarcodeLookupService;
import com.calorietracker.service.CheatDayService;
import com.calorietracker.service.FoodImageRecognitionService;
import com.calorietracker.service.ToolsRecommendationService;
import com.calorietracker.service.VoiceFoodResolveService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tools")
@Validated
public class ToolsController {

    private final ToolsRecommendationService toolsRecommendationService;
    private final CheatDayService cheatDayService;
    private final BarcodeLookupService barcodeLookupService;
    private final FoodImageRecognitionService foodImageRecognitionService;
    private final VoiceFoodResolveService voiceFoodResolveService;

    public ToolsController(
        ToolsRecommendationService toolsRecommendationService,
        CheatDayService cheatDayService,
        BarcodeLookupService barcodeLookupService,
        FoodImageRecognitionService foodImageRecognitionService,
        VoiceFoodResolveService voiceFoodResolveService
    ) {
        this.toolsRecommendationService = toolsRecommendationService;
        this.cheatDayService = cheatDayService;
        this.barcodeLookupService = barcodeLookupService;
        this.foodImageRecognitionService = foodImageRecognitionService;
        this.voiceFoodResolveService = voiceFoodResolveService;
    }

    @PostMapping("/recommendations")
    public DeficiencyToolsResponse getRecommendations(@RequestBody DeficiencyToolsRequest request) {
        DeficiencyToolsRequest payload = request == null ? new DeficiencyToolsRequest() : request;
        return toolsRecommendationService.buildRecommendations(payload);
    }

    @GetMapping("/currencies")
    public Map<String, Double> getCurrencies() {
        return toolsRecommendationService.supportedCurrencies();
    }

    @GetMapping("/deficiencies")
    public Map<String, java.util.List<String>> getDeficiencies() {
        return toolsRecommendationService.supportedDeficiencies();
    }

    @GetMapping("/barcode-lookup")
    public BarcodeLookupResponse barcodeLookup(@RequestParam String code) {
        return barcodeLookupService.lookupByBarcode(code);
    }

    @GetMapping("/voice-resolve")
    public Map<String, Object> resolveVoiceFood(
        @RequestParam String query,
        @RequestParam(required = false) Integer limit
    ) {
        return voiceFoodResolveService.resolve(query, limit);
    }

    @PostMapping(value = "/image-recognition", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageRecognitionResponse imageRecognition(
        @RequestPart("image") MultipartFile image,
        @RequestParam(required = false) String hint,
        @RequestParam(required = false) Integer limit
    ) {
        return foodImageRecognitionService.recognize(image, hint, limit);
    }

    @GetMapping("/cheat-days")
    public List<CheatDayEntryResponse> getCheatDays(
        @RequestParam Long userId,
        @RequestParam(required = false) Integer limit
    ) {
        return cheatDayService.listByUser(userId, limit);
    }

    @PostMapping("/cheat-days")
    public CheatDayEntryResponse upsertCheatDay(@Valid @RequestBody CheatDayUpsertRequest request) {
        return cheatDayService.upsert(request);
    }

    @DeleteMapping("/cheat-days")
    public Map<String, Object> deleteCheatDay(
        @RequestParam Long userId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        boolean deleted = cheatDayService.deleteByUserAndDate(userId, date);
        return Map.of("deleted", deleted, "date", date.toString());
    }
}

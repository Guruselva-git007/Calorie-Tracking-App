package com.calorietracker.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.RecentSearchItemResponse;
import com.calorietracker.dto.SearchActivityRecordRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.AuthService;
import com.calorietracker.service.UserSearchActivityService;

@RestController
@RequestMapping("/api/search-activity")
@Validated
public class SearchActivityController {

    private final AuthService authService;
    private final UserSearchActivityService userSearchActivityService;

    public SearchActivityController(AuthService authService, UserSearchActivityService userSearchActivityService) {
        this.authService = authService;
        this.userSearchActivityService = userSearchActivityService;
    }

    @PostMapping("/record")
    public void record(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestBody SearchActivityRecordRequest request
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        userSearchActivityService.record(user, request);
    }

    @GetMapping("/recent")
    public List<RecentSearchItemResponse> recent(
        @RequestHeader(name = "X-Auth-Token", required = false) String token,
        @RequestParam(required = false) Integer limit
    ) {
        AppUser user = authService.userFromTokenOrThrow(token);
        return userSearchActivityService.getRecent(user, limit);
    }
}

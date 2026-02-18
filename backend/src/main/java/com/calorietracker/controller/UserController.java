package com.calorietracker.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.UpdateUserProfileRequest;
import com.calorietracker.dto.UpdateGoalRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public AppUser getUser(@PathVariable Long id) {
        return userService.getUserOrThrow(id);
    }

    @PutMapping("/{id}/goal")
    public AppUser updateGoal(@PathVariable Long id, @Valid @RequestBody UpdateGoalRequest request) {
        return userService.updateGoal(id, request.getGoal());
    }

    @PutMapping("/{id}/profile")
    public AppUser updateProfile(@PathVariable Long id, @Valid @RequestBody UpdateUserProfileRequest request) {
        return userService.updateProfile(id, request);
    }
}

package com.calorietracker.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calorietracker.dto.AuthRequestCodeRequest;
import com.calorietracker.dto.AuthRequestCodeResponse;
import com.calorietracker.dto.AuthSessionResponse;
import com.calorietracker.dto.AuthVerifyCodeRequest;
import com.calorietracker.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/request-code")
    public AuthRequestCodeResponse requestCode(@Valid @RequestBody AuthRequestCodeRequest request) {
        return authService.requestCode(request);
    }

    @PostMapping("/verify-code")
    public AuthSessionResponse verifyCode(@Valid @RequestBody AuthVerifyCodeRequest request) {
        return authService.verifyCode(request);
    }

    @PostMapping("/guest")
    public AuthSessionResponse guestLogin(@RequestBody(required = false) AuthRequestCodeRequest request) {
        String preferredNickname = request == null ? null : request.getNickname();
        return authService.guestSession(preferredNickname);
    }

    @GetMapping("/me")
    public AuthSessionResponse me(@RequestHeader(name = "X-Auth-Token", required = false) String token) {
        return authService.me(token);
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader(name = "X-Auth-Token", required = false) String token) {
        authService.logout(token);
    }
}

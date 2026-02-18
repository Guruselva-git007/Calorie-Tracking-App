package com.calorietracker.dto;

import java.time.Instant;

import com.calorietracker.entity.AppUser;

public class AuthSessionResponse {

    private String token;
    private Instant expiresAt;
    private AppUser user;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }
}

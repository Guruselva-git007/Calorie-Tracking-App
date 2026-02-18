package com.calorietracker.dto;

import java.time.Instant;

public class AuthRequestCodeResponse {

    private String status;
    private String channel;
    private String targetMasked;
    private Instant expiresAt;
    private String devCode;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getTargetMasked() {
        return targetMasked;
    }

    public void setTargetMasked(String targetMasked) {
        this.targetMasked = targetMasked;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getDevCode() {
        return devCode;
    }

    public void setDevCode(String devCode) {
        this.devCode = devCode;
    }
}

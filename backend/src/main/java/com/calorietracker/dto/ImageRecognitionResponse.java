package com.calorietracker.dto;

import java.util.ArrayList;
import java.util.List;

public class ImageRecognitionResponse {

    private boolean found;
    private String engine;
    private String message;
    private String hintUsed;
    private String bestType;
    private Long bestId;
    private String bestName;
    private Double bestConfidence;
    private List<ImageRecognitionCandidateResponse> candidates = new ArrayList<>();

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHintUsed() {
        return hintUsed;
    }

    public void setHintUsed(String hintUsed) {
        this.hintUsed = hintUsed;
    }

    public String getBestType() {
        return bestType;
    }

    public void setBestType(String bestType) {
        this.bestType = bestType;
    }

    public Long getBestId() {
        return bestId;
    }

    public void setBestId(Long bestId) {
        this.bestId = bestId;
    }

    public String getBestName() {
        return bestName;
    }

    public void setBestName(String bestName) {
        this.bestName = bestName;
    }

    public Double getBestConfidence() {
        return bestConfidence;
    }

    public void setBestConfidence(Double bestConfidence) {
        this.bestConfidence = bestConfidence;
    }

    public List<ImageRecognitionCandidateResponse> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<ImageRecognitionCandidateResponse> candidates) {
        this.candidates = candidates;
    }
}

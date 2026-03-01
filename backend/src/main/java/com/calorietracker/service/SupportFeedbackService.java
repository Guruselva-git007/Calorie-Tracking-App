package com.calorietracker.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.calorietracker.dto.SupportFeedbackRequest;
import com.calorietracker.dto.SupportFeedbackResponse;
import com.calorietracker.entity.SupportFeedback;
import com.calorietracker.repository.SupportFeedbackRepository;

@Service
public class SupportFeedbackService {

    private final SupportFeedbackRepository supportFeedbackRepository;

    public SupportFeedbackService(SupportFeedbackRepository supportFeedbackRepository) {
        this.supportFeedbackRepository = supportFeedbackRepository;
    }

    public SupportFeedbackResponse submit(SupportFeedbackRequest request) {
        SupportFeedback feedback = new SupportFeedback();
        feedback.setUserId(request.getUserId());
        feedback.setUserName(sanitize(request.getUserName(), 140));
        feedback.setEmail(sanitize(request.getEmail(), 180));
        feedback.setCategory(normalizeCategory(request.getCategory()));
        feedback.setPageContext(sanitize(request.getPageContext(), 80));
        feedback.setSource(sanitize(request.getSource(), 80));
        feedback.setMessage(sanitizeMessage(request.getMessage()));

        SupportFeedback saved = supportFeedbackRepository.save(feedback);

        SupportFeedbackResponse response = new SupportFeedbackResponse();
        response.setId(saved.getId());
        response.setStatus("received");
        response.setMessage("Feedback saved. Thank you.");
        response.setCreatedAt(saved.getCreatedAt() == null ? null : saved.getCreatedAt().toString());
        return response;
    }

    public Map<String, Object> quickHelp() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(
            "tips",
            List.of(
                "Use Quick Logger on Home for fastest food entry.",
                "Use Tools for deficiency recommendations and BMI insights.",
                "Use Settings to update profile, preferences, and support options."
            )
        );
        payload.put(
            "quickTasks",
            List.of(
                "Go to Home",
                "Open Tools",
                "Open Settings",
                "Run dataset refresh",
                "Send feedback"
            )
        );
        payload.put("supportMessage", "Use chatbot feedback to report bugs or feature requests instantly.");
        return payload;
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String sanitizeMessage(String value) {
        String safe = sanitize(value, 1600);
        if (safe == null) {
            return "";
        }
        return safe;
    }

    private String normalizeCategory(String value) {
        String safe = sanitize(value, 64);
        if (safe == null) {
            return "general";
        }
        return safe.toLowerCase();
    }
}

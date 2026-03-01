package com.calorietracker.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.UpdateUserProfileRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.repository.AppUserRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UserService {

    private final AppUserRepository appUserRepository;

    public UserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional(readOnly = true)
    public AppUser getUserOrThrow(Long id) {
        return appUserRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found: " + id));
    }

    @Transactional
    public AppUser updateGoal(Long id, Integer goal) {
        AppUser user = getUserOrThrow(id);
        user.setDailyCalorieGoal(goal);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser updateProfile(Long id, UpdateUserProfileRequest request) {
        AppUser user = getUserOrThrow(id);

        if (StringUtils.hasText(request.getName())) {
            user.setName(request.getName().trim());
        }
        if (request.getNickname() != null) {
            user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname().trim() : null);
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        if (request.getBloodGroup() != null) {
            user.setBloodGroup(StringUtils.hasText(request.getBloodGroup()) ? request.getBloodGroup().trim() : null);
        }
        if (request.getHeightCm() != null) {
            user.setHeightCm(request.getHeightCm());
        }
        if (request.getWeightKg() != null) {
            user.setWeightKg(request.getWeightKg());
        }
        if (request.getRegion() != null) {
            user.setRegion(StringUtils.hasText(request.getRegion()) ? request.getRegion().trim() : null);
        }
        if (request.getState() != null) {
            user.setState(StringUtils.hasText(request.getState()) ? request.getState().trim() : null);
        }
        if (request.getCity() != null) {
            user.setCity(StringUtils.hasText(request.getCity()) ? request.getCity().trim() : null);
        }
        if (request.getNutritionDeficiency() != null) {
            user.setNutritionDeficiency(
                StringUtils.hasText(request.getNutritionDeficiency()) ? request.getNutritionDeficiency().trim() : null
            );
        }
        if (request.getMedicalIllness() != null) {
            user.setMedicalIllness(
                StringUtils.hasText(request.getMedicalIllness()) ? request.getMedicalIllness().trim() : null
            );
        }
        if (request.getLikedFoods() != null) {
            user.setLikedFoods(
                StringUtils.hasText(request.getLikedFoods()) ? request.getLikedFoods().trim() : null
            );
        }
        if (request.getProfileImageUrl() != null) {
            user.setProfileImageUrl(sanitizeProfileImageUrl(request.getProfileImageUrl()));
        }

        return appUserRepository.save(user);
    }

    private String sanitizeProfileImageUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String candidate = value.trim();
        String lowered = candidate.toLowerCase();

        if (lowered.startsWith("data:image/")) {
            if (candidate.length() > 50000) {
                throw new ResponseStatusException(BAD_REQUEST, "Profile image is too large. Use a smaller image.");
            }
            return candidate;
        }

        if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
            if (candidate.length() > 2000) {
                throw new ResponseStatusException(BAD_REQUEST, "Profile image URL is too long.");
            }
            return candidate;
        }

        if (lowered.startsWith("/api/media/") || lowered.startsWith("api/media/")) {
            if (candidate.length() > 2000) {
                throw new ResponseStatusException(BAD_REQUEST, "Profile image path is too long.");
            }
            return candidate.startsWith("/") ? candidate : "/" + candidate;
        }

        throw new ResponseStatusException(
            BAD_REQUEST,
            "Invalid profile image format. Use image URL or uploaded image."
        );
    }
}

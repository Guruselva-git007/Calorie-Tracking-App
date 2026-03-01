package com.calorietracker.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.calorietracker.dto.RecentSearchItemResponse;
import com.calorietracker.dto.SearchActivityRecordRequest;
import com.calorietracker.entity.AppUser;
import com.calorietracker.entity.SearchActivityItemType;
import com.calorietracker.entity.UserSearchActivity;
import com.calorietracker.repository.UserSearchActivityRepository;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class UserSearchActivityService {

    private static final String GUEST_EMAIL = "guest@calorietracker.local";

    private final UserSearchActivityRepository userSearchActivityRepository;

    public UserSearchActivityService(UserSearchActivityRepository userSearchActivityRepository) {
        this.userSearchActivityRepository = userSearchActivityRepository;
    }

    @Transactional
    public void record(AppUser user, SearchActivityRecordRequest request) {
        ensureSignedInUser(user);
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Search activity payload is required.");
        }

        if (request.getItemId() == null || request.getItemId() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "itemId must be greater than 0.");
        }

        SearchActivityItemType itemType = parseItemType(request.getItemType());
        String itemName = StringUtils.hasText(request.getItemName()) ? request.getItemName().trim() : "Food";
        if (itemName.length() > 180) {
            itemName = itemName.substring(0, 180);
        }

        UserSearchActivity activity = userSearchActivityRepository
            .findFirstByUserIdAndItemTypeAndItemId(user.getId(), itemType, request.getItemId())
            .orElseGet(UserSearchActivity::new);

        activity.setUser(user);
        activity.setItemType(itemType);
        activity.setItemId(request.getItemId());
        activity.setItemName(itemName);
        activity.setUsageCount((activity.getUsageCount() == null ? 0 : activity.getUsageCount()) + 1);
        activity.setLastSearchedAt(Instant.now());

        userSearchActivityRepository.save(activity);
    }

    @Transactional(readOnly = true)
    public List<RecentSearchItemResponse> getRecent(AppUser user, Integer limit) {
        ensureSignedInUser(user);

        int max = limit == null || limit <= 0 ? 16 : Math.min(limit, 60);
        return userSearchActivityRepository.findByUserIdOrderByLastSearchedAtDesc(user.getId(), PageRequest.of(0, max)).stream()
            .map(this::toResponse)
            .toList();
    }

    private RecentSearchItemResponse toResponse(UserSearchActivity activity) {
        RecentSearchItemResponse response = new RecentSearchItemResponse();
        response.setItemType(activity.getItemType() == null ? "" : activity.getItemType().name());
        response.setItemId(activity.getItemId());
        response.setItemName(activity.getItemName());
        response.setUsageCount(activity.getUsageCount());
        response.setLastSearchedAt(activity.getLastSearchedAt());
        return response;
    }

    private SearchActivityItemType parseItemType(String itemType) {
        if (!StringUtils.hasText(itemType)) {
            throw new ResponseStatusException(BAD_REQUEST, "itemType is required.");
        }

        try {
            return SearchActivityItemType.valueOf(itemType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown itemType: " + itemType);
        }
    }

    private void ensureSignedInUser(AppUser user) {
        if (user == null || isGuestUser(user)) {
            throw new ResponseStatusException(FORBIDDEN, "Sign in with your account to use personalized recent search history.");
        }
    }

    private boolean isGuestUser(AppUser user) {
        if (user == null) {
            return true;
        }
        return StringUtils.hasText(user.getEmail())
            && user.getEmail().trim().toLowerCase(Locale.ROOT).equals(GUEST_EMAIL);
    }
}

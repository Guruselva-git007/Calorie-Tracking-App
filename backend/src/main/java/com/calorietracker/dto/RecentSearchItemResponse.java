package com.calorietracker.dto;

import java.time.Instant;

public class RecentSearchItemResponse {

    private String itemType;
    private Long itemId;
    private String itemName;
    private Integer usageCount;
    private Instant lastSearchedAt;

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }

    public Instant getLastSearchedAt() {
        return lastSearchedAt;
    }

    public void setLastSearchedAt(Instant lastSearchedAt) {
        this.lastSearchedAt = lastSearchedAt;
    }
}

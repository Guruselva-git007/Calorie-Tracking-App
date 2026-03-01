package com.calorietracker.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "search_learned_alias",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_search_alias_normalized", columnNames = {"normalized_alias"})
    },
    indexes = {
        @Index(name = "idx_search_alias_domain", columnList = "domain"),
        @Index(name = "idx_search_alias_updated", columnList = "updated_at")
    }
)
public class SearchLearnedAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alias_text", nullable = false, length = 180)
    private String aliasText;

    @Column(name = "normalized_alias", nullable = false, length = 180)
    private String normalizedAlias;

    @Column(name = "canonical_text", nullable = false, length = 180)
    private String canonicalText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SearchLearningDomain domain = SearchLearningDomain.BOTH;

    @Column(nullable = false, length = 40)
    private String source = "AUTO_LEARNED";

    @Column(nullable = false)
    private Double confidence = 0.0;

    @Column(nullable = false)
    private Integer usageCount = 0;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant lastUsedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lastUsedAt == null) {
            lastUsedAt = now;
        }
        if (usageCount == null) {
            usageCount = 0;
        }
        if (confidence == null) {
            confidence = 0.0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAliasText() {
        return aliasText;
    }

    public void setAliasText(String aliasText) {
        this.aliasText = aliasText;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public void setNormalizedAlias(String normalizedAlias) {
        this.normalizedAlias = normalizedAlias;
    }

    public String getCanonicalText() {
        return canonicalText;
    }

    public void setCanonicalText(String canonicalText) {
        this.canonicalText = canonicalText;
    }

    public SearchLearningDomain getDomain() {
        return domain;
    }

    public void setDomain(SearchLearningDomain domain) {
        this.domain = domain;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}


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
    name = "search_miss_log",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_search_miss_domain_query", columnNames = {"domain", "normalized_query"})
    },
    indexes = {
        @Index(name = "idx_search_miss_last_seen", columnList = "last_seen_at"),
        @Index(name = "idx_search_miss_domain", columnList = "domain")
    }
)
public class SearchMissLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SearchLearningDomain domain = SearchLearningDomain.BOTH;

    @Column(name = "query_text", nullable = false, length = 180)
    private String queryText;

    @Column(name = "normalized_query", nullable = false, length = 180)
    private String normalizedQuery;

    @Column(nullable = false)
    private Integer missCount = 0;

    @Column(nullable = false)
    private Integer hitCount = 0;

    @Column(name = "best_candidate", length = 180)
    private String bestCandidate;

    @Column(name = "best_confidence")
    private Double bestConfidence;

    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (missCount == null) {
            missCount = 0;
        }
        if (hitCount == null) {
            hitCount = 0;
        }
        if (bestConfidence == null) {
            bestConfidence = 0.0;
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

    public SearchLearningDomain getDomain() {
        return domain;
    }

    public void setDomain(SearchLearningDomain domain) {
        this.domain = domain;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public void setNormalizedQuery(String normalizedQuery) {
        this.normalizedQuery = normalizedQuery;
    }

    public Integer getMissCount() {
        return missCount;
    }

    public void setMissCount(Integer missCount) {
        this.missCount = missCount;
    }

    public Integer getHitCount() {
        return hitCount;
    }

    public void setHitCount(Integer hitCount) {
        this.hitCount = hitCount;
    }

    public String getBestCandidate() {
        return bestCandidate;
    }

    public void setBestCandidate(String bestCandidate) {
        this.bestCandidate = bestCandidate;
    }

    public Double getBestConfidence() {
        return bestConfidence;
    }

    public void setBestConfidence(Double bestConfidence) {
        this.bestConfidence = bestConfidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
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
}


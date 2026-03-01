package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.SearchLearningDomain;
import com.calorietracker.entity.SearchMissLog;

public interface SearchMissLogRepository extends JpaRepository<SearchMissLog, Long> {

    Optional<SearchMissLog> findFirstByDomainAndNormalizedQuery(SearchLearningDomain domain, String normalizedQuery);
}


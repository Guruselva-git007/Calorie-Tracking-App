package com.calorietracker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.SearchLearnedAlias;

public interface SearchLearnedAliasRepository extends JpaRepository<SearchLearnedAlias, Long> {

    Optional<SearchLearnedAlias> findFirstByNormalizedAlias(String normalizedAlias);
}


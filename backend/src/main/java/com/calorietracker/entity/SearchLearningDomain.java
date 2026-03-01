package com.calorietracker.entity;

public enum SearchLearningDomain {
    INGREDIENT,
    DISH,
    BOTH;

    public boolean matches(SearchLearningDomain requested) {
        if (requested == null) {
            return true;
        }
        return this == BOTH || requested == BOTH || this == requested;
    }
}


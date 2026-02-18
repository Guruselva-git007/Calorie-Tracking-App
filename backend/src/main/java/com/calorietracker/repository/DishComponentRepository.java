package com.calorietracker.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.calorietracker.entity.Dish;
import com.calorietracker.entity.DishComponent;

public interface DishComponentRepository extends JpaRepository<DishComponent, Long> {

    List<DishComponent> findByDishOrderByIdAsc(Dish dish);
}

package com.calorietracker.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.calorietracker.entity.Dish;
import com.calorietracker.entity.DishComponent;

public interface DishComponentRepository extends JpaRepository<DishComponent, Long> {

    List<DishComponent> findByDishOrderByIdAsc(Dish dish);

    @Query("select dc from DishComponent dc join fetch dc.ingredient where dc.dish in :dishes order by dc.dish.id asc, dc.id asc")
    List<DishComponent> findByDishInWithIngredientOrderByDishIdAscIdAsc(@Param("dishes") List<Dish> dishes);
}

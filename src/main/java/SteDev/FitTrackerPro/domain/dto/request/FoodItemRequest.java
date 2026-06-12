package SteDev.FitTrackerPro.domain.dto.request;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record FoodItemRequest(
        @NotBlank String name,
        String barcode,
        @NotNull BigDecimal caloriesPer100g,
        @NotNull BigDecimal proteinPer100g,
        @NotNull BigDecimal carbPer100g,
        @NotNull BigDecimal fatPer100g,
        @NotNull BigDecimal fiberPer100g
) {}

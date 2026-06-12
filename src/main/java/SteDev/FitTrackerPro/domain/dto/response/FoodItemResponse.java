package SteDev.FitTrackerPro.domain.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record FoodItemResponse(
        UUID id,
        String name,
        String barcode,
        BigDecimal caloriesPer100g,
        BigDecimal proteinPer100g,
        BigDecimal carbPer100g,
        BigDecimal fatPer100g,
        BigDecimal fiberPer100g,
        LocalDateTime createdAt,
        LocalDateTime updatedAt

) {
}

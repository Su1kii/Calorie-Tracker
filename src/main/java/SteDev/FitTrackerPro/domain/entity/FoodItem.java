package SteDev.FitTrackerPro.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "food_items")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodItem {
    @Id
    @UuidGenerator
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Column(unique = true)
    private String barcode;
    @Column(name = "calories_per_100g", nullable = false, precision = 10, scale = 4)
    private BigDecimal caloriesPer100g;
    @Column(name = "protein_per_100g", nullable = false, precision = 10, scale = 4)
    private BigDecimal proteinPer100g;
    @Column(name = "carb_per_100g", nullable = false, precision = 10, scale = 4)
    private BigDecimal carbPer100g;
    @Column(name = "fat_per_100g", nullable = false, precision = 10, scale = 4)
    private BigDecimal fatPer100g;
    @Column(name = "fiber_per_100g", nullable = false, precision = 10, scale = 4)
    private BigDecimal fiberPer100g;
    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

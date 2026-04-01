package dev.stev.calorie_tracker.domain.DTOs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TrackingCreateDTO {
    private Integer userId;
    private double calories;
    private double protein;
    private double weight;
    private LocalDate date;

}

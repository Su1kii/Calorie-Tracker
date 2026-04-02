package dev.stev.calorie_tracker.mappers;

import dev.stev.calorie_tracker.domain.DTOs.TrackingCreateDTO;
import dev.stev.calorie_tracker.domain.entities.Tracking;
import org.springframework.stereotype.Component;

@Component
public class TrackingMapper {

    // Tracking entity → response to client
    public TrackingCreateDTO toDTO(Tracking tracking) {
        return TrackingCreateDTO.builder()
                .calories(tracking.getCalories())
                .protein(tracking.getProtein())
                .weight(tracking.getWeight())
                .date(tracking.getDate())
                .build();
    }

    public Tracking toEntity(TrackingCreateDTO dto) {
        return Tracking.builder()
                .calories(dto.getCalories())
                .protein(dto.getProtein())
                .weight(dto.getWeight())
                .date(dto.getDate())
                .build();
    }
}

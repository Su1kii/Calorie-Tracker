package SteDev.FitTrackerPro.domain.dto.response;

import SteDev.FitTrackerPro.domain.enums.Role;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Role role,
        LocalDateTime createdAt
) {}
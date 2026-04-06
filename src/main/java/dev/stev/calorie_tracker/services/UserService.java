package dev.stev.calorie_tracker.services;

import dev.stev.calorie_tracker.domain.DTOs.UserCreateDTO;
import dev.stev.calorie_tracker.domain.DTOs.UserResponseDTO;

public interface UserService {
    UserResponseDTO getUserById(Integer id);
}

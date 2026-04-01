package dev.stev.calorie_tracker.mappers;

import dev.stev.calorie_tracker.domain.DTOs.UserCreateDTO;
import dev.stev.calorie_tracker.domain.DTOs.UserResponseDTO;
import dev.stev.calorie_tracker.domain.entities.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    // User entity → response to client
    public UserResponseDTO toDTO(User user) {
        return UserResponseDTO.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .build();
    }

    // Registration request → entity to save
    public User toEntity(UserCreateDTO dto) {
        return User.builder()
                .name(dto.getName())
                .email(dto.getEmail())
                .hashedPassword(dto.getPassword()) // service will hash it before calling this
                .build();
    }
}

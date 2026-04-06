package dev.stev.calorie_tracker.services.Impl;

import dev.stev.calorie_tracker.domain.DTOs.UserCreateDTO;
import dev.stev.calorie_tracker.domain.DTOs.UserResponseDTO;
import dev.stev.calorie_tracker.domain.entities.User;
import dev.stev.calorie_tracker.mappers.UserMapper;
import dev.stev.calorie_tracker.repository.UserRepo;
import dev.stev.calorie_tracker.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepo userRepo;
    private final UserMapper userMapper;

    @Override
    public UserResponseDTO getUserById(Integer id) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return userMapper.toDTO(user);
    }
}

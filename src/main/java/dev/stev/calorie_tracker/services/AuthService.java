package dev.stev.calorie_tracker.services;

import dev.stev.calorie_tracker.domain.DTOs.AuthRequestDTO;
import dev.stev.calorie_tracker.domain.DTOs.AuthResponseDTO;
import dev.stev.calorie_tracker.domain.DTOs.UserCreateDTO;
import dev.stev.calorie_tracker.domain.entities.User;
import dev.stev.calorie_tracker.mappers.UserMapper;
import dev.stev.calorie_tracker.repository.UserRepo;
import dev.stev.calorie_tracker.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepo userRepo;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponseDTO register(UserCreateDTO dto) {
        // Convert DTO to entity
        User user = userMapper.toEntity(dto);

        // Hash the password before saving
        user.setHashedPassword(passwordEncoder.encode(dto.getPassword()));

        // Save user to DB
        userRepo.save(user);

        // Generate token using their email
        String token = jwtService.generateToken(user.getEmail());

        // Return the token
        return AuthResponseDTO.builder()
                .token(token)
                .build();
    }

    public AuthResponseDTO login(AuthRequestDTO request) {
        // This checks email + password — throws exception if wrong
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        // If we get here authentication passed — load the user
        User user = userRepo.findByEmail(request.getEmail())
                .orElseThrow();

        // Generate token
        String token = jwtService.generateToken(user.getEmail());

        // Return token
        return AuthResponseDTO.builder()
                .token(token)
                .build();
    }
}
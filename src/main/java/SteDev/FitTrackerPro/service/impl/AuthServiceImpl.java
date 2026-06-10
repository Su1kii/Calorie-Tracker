package SteDev.FitTrackerPro.service.impl;

import SteDev.FitTrackerPro.domain.dto.request.LoginRequest;
import SteDev.FitTrackerPro.domain.dto.request.RegisterRequest;
import SteDev.FitTrackerPro.domain.dto.response.AuthResponse;
import SteDev.FitTrackerPro.domain.entity.RefreshToken;
import SteDev.FitTrackerPro.domain.entity.User;
import SteDev.FitTrackerPro.domain.enums.Role;
import SteDev.FitTrackerPro.exception.DuplicateEmailException;
import SteDev.FitTrackerPro.exception.InvalidCredentialsException;
import SteDev.FitTrackerPro.exception.InvalidTokenException;
import SteDev.FitTrackerPro.exception.ResourceNotFoundException;
import SteDev.FitTrackerPro.repository.RefreshTokenRepository;
import SteDev.FitTrackerPro.repository.UserRepository;
import SteDev.FitTrackerPro.security.JwtService;
import SteDev.FitTrackerPro.service.AuthService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthenticationManager authenticationManager;

    @Transactional
    @Override
    public AuthResponse register(RegisterRequest request) {
        String email = request.email();
        String password = request.password();

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("Email already exists");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(Role.USER)
                .isActive(true)
                .build();
        userRepository.save(user);
        String accessToken = jwtService.generateAccessToken(user);
        String refreshTokenString = jwtService.generateRefreshToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenString)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenString, 900);


    }

    @Transactional
    @Override
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.email(),
                            request.password()
                    )
            );
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String accessToken = jwtService.generateAccessToken(user);
        String refreshTokenString = jwtService.generateRefreshToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenString)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenString, 900);

    }

    @Override
    public AuthResponse refreshToken(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));
        if (refreshToken.isRevoked()) {
            throw new InvalidTokenException("revoked");
        }
        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("expired");
        }

        User user = refreshToken.getUser();
        String newAccessToken = jwtService.generateAccessToken(user);
        return new AuthResponse(newAccessToken, token, 900);
    }

    @Override
    public void logout(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);


    }
}

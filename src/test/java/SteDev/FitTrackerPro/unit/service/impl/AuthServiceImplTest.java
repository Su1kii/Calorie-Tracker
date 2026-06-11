package SteDev.FitTrackerPro.unit.service.impl;

import SteDev.FitTrackerPro.domain.dto.request.LoginRequest;
import SteDev.FitTrackerPro.domain.dto.request.RegisterRequest;
import SteDev.FitTrackerPro.domain.dto.response.AuthResponse;
import SteDev.FitTrackerPro.domain.entity.RefreshToken;
import SteDev.FitTrackerPro.domain.entity.User;
import SteDev.FitTrackerPro.domain.enums.Role;
import SteDev.FitTrackerPro.exception.DuplicateEmailException;
import SteDev.FitTrackerPro.exception.InvalidCredentialsException;
import SteDev.FitTrackerPro.exception.InvalidTokenException;
import SteDev.FitTrackerPro.repository.RefreshTokenRepository;
import SteDev.FitTrackerPro.repository.UserRepository;
import SteDev.FitTrackerPro.security.JwtService;
import SteDev.FitTrackerPro.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void register_shouldThrowDuplicateEmailException_whenEmailAlreadyExists() {
        // ARRANGE — set up the scenario
        RegisterRequest request = new RegisterRequest(
                "test@test.com", "password123", "John", "Doe");
        when(userRepository.existsByEmail("test@test.com")).thenReturn(true);

        // ACT + ASSERT — call the method and verify the exception
        assertThrows(DuplicateEmailException.class,
                () -> authService.register(request));
    }

    @Test
    void register_shouldReturnAuthResponse_whenSuccessful() {
        // ARRANGE
        RegisterRequest request = new RegisterRequest(
                "test@test.com", "password123", "John", "Doe");

        User mockUser = User.builder()
                .email("test@test.com")
                .passwordHash("hashedPassword")
                .firstName("John")
                .lastName("Doe")
                .role(Role.USER)
                .isActive(true)
                .build();

        when(userRepository.existsByEmail("test@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(mockUser);
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("accessToken");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("refreshToken");

        // ACT
        AuthResponse response = authService.register(request);

        // ASSERT
        assertNotNull(response);
        assertEquals("accessToken", response.accessToken());
        assertEquals("refreshToken", response.refreshToken());
    }

    @Test
    void login_shouldReturnAuthResponse_whenSuccessful() {
        LoginRequest request = new LoginRequest("test@test.com", "rightPassword");

        User mockUser = User.builder()
                .email("test@test.com")
                .passwordHash("rightPassword")
                .firstName("John")
                .lastName("Doe")
                .role(Role.USER)
                .isActive(true)
                .build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(mockUser));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("accessToken");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("refreshToken");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("accessToken", response.accessToken());
        assertEquals("refreshToken", response.refreshToken());
    }

    @Test
    void login_shouldThrowInvalidCredentialsException_whenBadCredentials() {
        LoginRequest request = new LoginRequest("test@test.com", "wrongPassword");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(request));
    }

    @Test
    void refreshToken_shouldThrowInvalidTokenException_whenTokenNotFound() {
        when(refreshTokenRepository.findByToken("someToken")).thenReturn(Optional.empty());

        assertThrows(InvalidTokenException.class,
                () -> authService.refreshToken("someToken"));

    }


    @Test
    void refreshToken_shouldThrowInvalidTokenException_whenTokenRevoked() {
        RefreshToken revokedToken = RefreshToken.builder()
                .token("someToken")
                .isRevoked(true)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();

        when(refreshTokenRepository.findByToken("someToken"))
                .thenReturn(Optional.of(revokedToken));

        assertThrows(InvalidTokenException.class,
                () -> authService.refreshToken("someToken"));
    }

    @Test
    void refreshToken_shouldThrowInvalidTokenException_whenTokenExpired(){
        RefreshToken expiredToken = RefreshToken.builder()
                .token("someToken")
                .isRevoked(false)
                .expiresAt(LocalDateTime.now().minusDays(7))
                .build();

        when(refreshTokenRepository.findByToken("someToken"))
                .thenReturn(Optional.of(expiredToken));

        assertThrows(InvalidTokenException.class,
                () -> authService.refreshToken("someToken"));

    }

    @Test
    void refreshToken_shouldReturnNewAccessToken_whenValid() {
        User mockUser = User.builder()
                .email("test@test.com")
                .passwordHash("hashedPassword")
                .firstName("John")
                .lastName("Doe")
                .role(Role.USER)
                .isActive(true)
                .build();

        RefreshToken validToken = RefreshToken.builder()
                .token("someToken")
                .user(mockUser)
                .isRevoked(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();

        when(refreshTokenRepository.findByToken("someToken"))
                .thenReturn(Optional.of(validToken));
        when(jwtService.generateAccessToken(any())).thenReturn("newAccessToken");

        AuthResponse response = authService.refreshToken("someToken");

        assertEquals("newAccessToken", response.accessToken());
        assertEquals("someToken", response.refreshToken());
    }

    @Test
    void logout_shouldRevokeToken() {
        // ARRANGE — a valid unrevoked token exists in the repo
        RefreshToken refreshToken = RefreshToken.builder()
                .token("someToken")
                .isRevoked(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();

        when(refreshTokenRepository.findByToken("someToken"))
                .thenReturn(Optional.of(refreshToken));

        // ACT — call logout
        authService.logout("someToken");

        // ASSERT — verify save was called (meaning isRevoked was set and saved)
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

}

package SteDev.FitTrackerPro.service;

import SteDev.FitTrackerPro.domain.dto.request.LoginRequest;
import SteDev.FitTrackerPro.domain.dto.request.RegisterRequest;
import SteDev.FitTrackerPro.domain.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    AuthResponse refreshToken(String token);
    void logout(String token);
}

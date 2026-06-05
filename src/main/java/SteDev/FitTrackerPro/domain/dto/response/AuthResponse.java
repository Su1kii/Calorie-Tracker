package SteDev.FitTrackerPro.domain.dto.response;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        Integer expiresIn
) {
}

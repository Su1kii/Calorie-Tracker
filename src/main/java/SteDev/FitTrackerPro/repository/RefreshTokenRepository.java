package SteDev.FitTrackerPro.repository;

import SteDev.FitTrackerPro.domain.entity.RefreshToken;
import SteDev.FitTrackerPro.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);
    List<RefreshToken> findByUserAndIsRevokedFalse(User user);
}
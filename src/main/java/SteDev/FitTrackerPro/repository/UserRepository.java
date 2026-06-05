package SteDev.FitTrackerPro.repository;

import SteDev.FitTrackerPro.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
//    Optional  forces the caller to handle both cases explicitly. (present (user found) or empty (user not found))
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

}


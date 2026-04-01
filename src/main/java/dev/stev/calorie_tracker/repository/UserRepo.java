package dev.stev.calorie_tracker.repository;

import dev.stev.calorie_tracker.domain.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepo extends JpaRepository<User,Integer> {
//    TODO:CUSTOM QUERIES
}

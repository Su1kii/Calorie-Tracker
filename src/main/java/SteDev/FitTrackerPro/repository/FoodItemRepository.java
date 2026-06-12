package SteDev.FitTrackerPro.repository;

import SteDev.FitTrackerPro.domain.entity.FoodItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FoodItemRepository  extends JpaRepository<FoodItem, UUID> {
    @Query(value = "SELECT * FROM food_items WHERE name ILIKE %:query% ORDER BY similarity(name, :query) DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<FoodItem> searchByName(@Param("query") String query, @Param("limit") int limit, @Param("offset") int offset);
    Optional<FoodItem> findByBarcode(String barcode);
}

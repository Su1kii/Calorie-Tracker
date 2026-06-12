package SteDev.FitTrackerPro.mapper;

import SteDev.FitTrackerPro.domain.dto.response.FoodItemResponse;
import SteDev.FitTrackerPro.domain.entity.FoodItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FoodItemMapper {
    FoodItemResponse toFoodItemResponse(FoodItem foodItem);
}

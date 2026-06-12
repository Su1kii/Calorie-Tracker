package SteDev.FitTrackerPro.service;

import SteDev.FitTrackerPro.domain.dto.request.FoodItemRequest;

import SteDev.FitTrackerPro.domain.dto.response.FoodItemResponse;
import org.springframework.data.domain.Page;


import java.util.UUID;

public interface FoodItemService {
    Page<FoodItemResponse> searchByName(String query, int page, int size);
    FoodItemResponse findById(UUID id);
    FoodItemResponse findByBarcode(String barcode);
    FoodItemResponse createFoodItem(FoodItemRequest request);
}

package SteDev.FitTrackerPro.service.impl;

import SteDev.FitTrackerPro.domain.dto.request.FoodItemRequest;
import SteDev.FitTrackerPro.domain.dto.response.FoodItemResponse;
import SteDev.FitTrackerPro.domain.entity.FoodItem;
import SteDev.FitTrackerPro.exception.ResourceNotFoundException;
import SteDev.FitTrackerPro.mapper.FoodItemMapper;
import SteDev.FitTrackerPro.repository.FoodItemRepository;
import SteDev.FitTrackerPro.service.FoodItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FoodItemServiceImpl implements FoodItemService {

    private final FoodItemRepository foodItemRepository;
    private final FoodItemMapper foodItemMapper;

    @Override
    public Page<FoodItemResponse> searchByName(String query, int page, int size) {
        List<FoodItem> foodItems = foodItemRepository.searchByName(query, size, page * size);
        List<FoodItemResponse> responses = foodItems.stream()
                .map(foodItemMapper::toFoodItemResponse)
                .toList();
        return new PageImpl<>(responses, PageRequest.of(page, size), responses.size());
    }

    @Override
    public FoodItemResponse findById(UUID id) {
        FoodItem response = foodItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FoodItem", id));
        return foodItemMapper.toFoodItemResponse(response);
    }
    @Override
    public FoodItemResponse findByBarcode(String barcode) {
        FoodItem response = foodItemRepository.findByBarcode(barcode)
                .orElseThrow(() -> new ResourceNotFoundException("FoodItem", barcode));
        return foodItemMapper.toFoodItemResponse(response);
    }

    @Transactional
    @Override
    public FoodItemResponse createFoodItem(FoodItemRequest request) {
        FoodItem foodItem = FoodItem.builder()
                .name(request.name())
                .barcode(request.barcode())
                .caloriesPer100g(request.caloriesPer100g())
                .proteinPer100g(request.proteinPer100g())
                .carbPer100g(request.carbPer100g())
                .fatPer100g(request.fatPer100g())
                .fiberPer100g(request.fiberPer100g())
                .build();

        FoodItem saved = foodItemRepository.save(foodItem);
        return foodItemMapper.toFoodItemResponse(saved);
    }
}

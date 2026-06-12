package SteDev.FitTrackerPro.controller;

import SteDev.FitTrackerPro.domain.dto.request.FoodItemRequest;
import SteDev.FitTrackerPro.domain.dto.response.FoodItemResponse;
import SteDev.FitTrackerPro.service.FoodItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/food-items")
@RequiredArgsConstructor
public class FoodItemController {

    private final FoodItemService foodItemService;

    @GetMapping("/search")
    public ResponseEntity<Page<FoodItemResponse>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(foodItemService.searchByName(q, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FoodItemResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(foodItemService.findById(id));
    }

    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<FoodItemResponse> findByBarcode(@PathVariable String barcode) {
        return ResponseEntity.ok(foodItemService.findByBarcode(barcode));
    }

    @PostMapping
    public ResponseEntity<FoodItemResponse> createFoodItem(
            @Valid @RequestBody FoodItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(foodItemService.createFoodItem(request));
    }
}


# FitTrack Pro — Barcode Scanner Implementation Guide

Add this after **Step 16** (FoodItem full stack is complete). Your `findByBarcode` endpoint already exists at that point. This guide covers the backend fallback logic first, then web and mobile client implementations.

---

## When to Add This

**Insert between Step 16 and Step 17.** The backend changes here are self-contained — they only touch `FoodItemService` and add one new service. Do not let this block your Meal domain work; if you want to move fast, implement the backend portion now and come back to the frontend (web or mobile) at the end of Phase 1 or after deployment in Step 22.

---

## Part 1 — Backend: DB Check + Open Food Facts Fallback

### Step 16A — OpenFoodFactsService

**What:** Create `service/impl/OpenFoodFactsServiceImpl.java`. This service calls the Open Food Facts public API when a barcode is not found in your database.

**API endpoint you're calling:**
```
GET https://world.openfoodfacts.org/api/v0/product/{barcode}.json
```

No API key required. Free, no rate limit for reasonable usage.

**Why a separate service class:** The same isolation principle that applies everywhere else. The HTTP call to Open Food Facts is infrastructure — it does not belong in `FoodItemServiceImpl`. A dedicated class keeps `FoodItemServiceImpl` clean and makes the external dependency mockable in tests.

**Implementation:**

```java
@Service
public class OpenFoodFactsServiceImpl {

    private final RestTemplate restTemplate;

    // Inject RestTemplate as a bean — configure it in a @Configuration class
    public OpenFoodFactsServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Optional<FoodItemResponse> lookupByBarcode(String barcode) {
        try {
            String url = "https://world.openfoodfacts.org/api/v0/product/" + barcode + ".json";
            OpenFoodFactsResponse response = restTemplate.getForObject(url, OpenFoodFactsResponse.class);

            if (response == null || response.getStatus() != 1 || response.getProduct() == null) {
                return Optional.empty(); // Product not found in Open Food Facts
            }

            return Optional.of(mapToFoodItemResponse(response.getProduct()));

        } catch (RestClientException e) {
            // Log the error — do not throw. A failed external call should not crash your API.
            log.warn("Open Food Facts lookup failed for barcode {}: {}", barcode, e.getMessage());
            return Optional.empty();
        }
    }

    private FoodItemResponse mapToFoodItemResponse(OpenFoodFactsProduct product) {
        // Map fields from Open Food Facts response to your FoodItemResponse DTO
        // Fields you care about: product_name, nutriments.energy-kcal_100g,
        // nutriments.proteins_100g, nutriments.carbohydrates_100g, nutriments.fat_100g,
        // nutriments.fiber_100g, code (the barcode)
    }
}
```

**DTOs to create** (these mirror Open Food Facts JSON structure):

```java
// OpenFoodFactsResponse.java
public class OpenFoodFactsResponse {
    private int status;           // 1 = found, 0 = not found
    private OpenFoodFactsProduct product;
}

// OpenFoodFactsProduct.java
public class OpenFoodFactsProduct {
    @JsonProperty("product_name")
    private String productName;
    private String code;          // the barcode
    private OpenFoodFactsNutriments nutriments;
}

// OpenFoodFactsNutriments.java
public class OpenFoodFactsNutriments {
    @JsonProperty("energy-kcal_100g")
    private BigDecimal caloriesPer100g;

    @JsonProperty("proteins_100g")
    private BigDecimal proteinPer100g;

    @JsonProperty("carbohydrates_100g")
    private BigDecimal carbsPer100g;

    @JsonProperty("fat_100g")
    private BigDecimal fatPer100g;

    @JsonProperty("fiber_100g")
    private BigDecimal fiberPer100g;
}
```

**Senior tip:** Open Food Facts data quality is inconsistent. Some products have null nutriment fields. Your mapper must handle every field as nullable — use `Optional.ofNullable()` and provide a `BigDecimal.ZERO` default for any null nutritional value. Do not let a null from their API become a NullPointerException in yours.

---

### Step 16B — Modify FoodItemServiceImpl.findByBarcode()

**What:** Add the fallback chain to `findByBarcode()`. The logic is: check your DB first → if miss, call Open Food Facts → if found, persist to your DB → return.

**Why persist on cache miss:** Once you've looked up a product from Open Food Facts, store it in your `food_items` table. The next time any user scans that barcode, it hits your DB directly with no external call. Your database self-populates from real user activity.

**Implementation:**

```java
@Transactional
public FoodItemResponse findByBarcode(String barcode) {

    // 1. Check your own database first
    Optional<FoodItem> localResult = foodItemRepository.findByBarcode(barcode);
    if (localResult.isPresent()) {
        return foodItemMapper.toResponse(localResult.get());
    }

    // 2. Miss — call Open Food Facts
    Optional<FoodItemResponse> externalResult = openFoodFactsService.lookupByBarcode(barcode);
    if (externalResult.isEmpty()) {
        throw new ResourceNotFoundException("No food item found for barcode: " + barcode);
    }

    // 3. Found externally — persist to your DB so future lookups are local
    FoodItem newItem = mapResponseToEntity(externalResult.get());
    newItem = foodItemRepository.save(newItem);

    return foodItemMapper.toResponse(newItem);
}
```

**The method is @Transactional** because the save on step 3 is a write. If the save fails, you still return the data from Open Food Facts — consider wrapping the save in a separate `try/catch` and logging the failure rather than rolling back the whole response. The user gets their food data either way; the persistence failure is a background concern.

---

### Step 16C — Update FoodItemController

**What:** No changes needed to the controller. `GET /api/v1/food-items/barcode/{barcode}` already exists from Step 16. The fallback is transparent to the caller — they always get a `FoodItemResponse` or a 404.

**One addition worth making:** Add a `?persist=false` optional query param if you want to let callers look up a barcode without triggering the auto-save. Useful for admin tooling later.

---

### Step 16D — RestTemplate Bean

**What:** Create `config/RestTemplateConfig.java`.

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    }
}
```

**Why timeouts matter:** If Open Food Facts is slow or down, your API cannot wait indefinitely. A 5-second read timeout means your `/barcode/{barcode}` endpoint will respond within ~5 seconds in the worst case (external failure), rather than hanging the request thread until the OS-level TCP timeout fires (which can be minutes). Tight timeouts are non-negotiable on any external HTTP call.

---

### Step 16E — Add Redis Caching for Barcode Lookups

**What:** After Phase 3 Redis work is done (Step 38), come back and add a cache layer in front of the barcode lookup. Cache key: `barcode:{barcodeString}`. TTL: 7 days (food item data does not change frequently).

This is optional until Phase 3 but note it here so you remember where it fits. Barcode lookups are read-heavy and the data is extremely stable — high TTL is correct here.

---

## Part 2 — Web Client (React + zxing-js)

### When: After Step 22 (Phase 1 deployed) or alongside it if you have a frontend.

### Library

```bash
npm install @zxing/library
```

`@zxing/library` uses the browser's `getUserMedia` API (camera access) to decode barcodes in real time from a video stream. No backend changes needed — it decodes client-side and fires a request to your existing endpoint.

### Component: BarcodeScanner.tsx

```tsx
import { useEffect, useRef, useState } from "react";
import { BrowserMultiFormatReader } from "@zxing/library";

interface BarcodeScannerProps {
  onResult: (barcode: string) => void;
  onError?: (error: string) => void;
}

export function BarcodeScanner({ onResult, onError }: BarcodeScannerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const readerRef = useRef<BrowserMultiFormatReader | null>(null);
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    readerRef.current = new BrowserMultiFormatReader();

    return () => {
      readerRef.current?.reset(); // Stop camera on unmount
    };
  }, []);

  const startScan = async () => {
    if (!videoRef.current || !readerRef.current) return;
    setScanning(true);

    try {
      await readerRef.current.decodeFromVideoDevice(
        undefined,           // undefined = use default camera
        videoRef.current,
        (result, error) => {
          if (result) {
            readerRef.current?.reset(); // Stop scanning after first result
            setScanning(false);
            onResult(result.getText()); // Pass barcode string up to parent
          }
          // Ignore continuous decode errors — they fire constantly when no barcode is in frame
        }
      );
    } catch (err) {
      setScanning(false);
      onError?.("Camera access denied or unavailable");
    }
  };

  return (
    <div>
      <video ref={videoRef} style={{ width: "100%", maxWidth: 400 }} />
      {!scanning && (
        <button onClick={startScan}>Scan Barcode</button>
      )}
      {scanning && <p>Point camera at barcode...</p>}
    </div>
  );
}
```

### Parent component: FoodSearch.tsx (how you wire it)

```tsx
async function handleBarcodeResult(barcode: string) {
  try {
    const response = await fetch(`/api/v1/food-items/barcode/${barcode}`, {
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (response.status === 404) {
      setError("Food item not found. Try searching by name.");
      return;
    }

    const foodItem = await response.json();
    setSelectedFood(foodItem); // Pre-fill the meal entry form
  } catch (err) {
    setError("Failed to look up barcode. Try again.");
  }
}

// In your JSX:
<BarcodeScanner
  onResult={handleBarcodeResult}
  onError={(msg) => setError(msg)}
/>
```

### Browser requirements

`getUserMedia` requires HTTPS. It will not work on `http://localhost` in most browsers — use `https://localhost` with a self-signed cert, or test on a deployed URL. In development, Chrome allows `http://localhost` as an exception; Safari does not.

---

## Part 3 — Mobile Client (React Native + react-native-vision-camera)

### When: If/when you build a mobile version. This is post-Phase 5 work for the portfolio.

### Library

```bash
npm install react-native-vision-camera
npm install vision-camera-code-scanner  # barcode scanning plugin for VisionCamera
```

Both require native module linking. Run `cd ios && pod install` after installation.

### Component: BarcodeScanner.tsx (React Native)

```tsx
import { useEffect, useRef } from "react";
import { Camera, useCameraDevices } from "react-native-vision-camera";
import { useScanBarcodes, BarcodeFormat } from "vision-camera-code-scanner";

interface BarcodeScannerProps {
  onBarcode: (barcode: string) => void;
}

export function BarcodeScanner({ onBarcode }: BarcodeScannerProps) {
  const devices = useCameraDevices();
  const device = devices.back; // Rear camera
  const hasScanned = useRef(false); // Prevent firing multiple times per scan

  const [frameProcessor, barcodes] = useScanBarcodes([
    BarcodeFormat.EAN_13,  // Standard grocery barcode
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,   // US product barcodes
    BarcodeFormat.UPC_E,
  ]);

  useEffect(() => {
    if (barcodes.length > 0 && !hasScanned.current) {
      const barcode = barcodes[0].displayValue;
      if (barcode) {
        hasScanned.current = true;
        onBarcode(barcode);
      }
    }
  }, [barcodes]);

  if (!device) return null;

  return (
    <Camera
      style={{ flex: 1 }}
      device={device}
      isActive={true}
      frameProcessor={frameProcessor}
      frameProcessorFps={5} // 5 fps is enough — no need to scan 60 times per second
    />
  );
}
```

### Permissions (React Native)

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Add to `ios/YourApp/Info.plist`:
```xml
<key>NSCameraUsageDescription</key>
<string>FitTrack needs camera access to scan food barcodes.</string>
```

Request at runtime before mounting the camera component:
```tsx
import { Camera } from "react-native-vision-camera";

const permission = await Camera.requestCameraPermission();
if (permission === "denied") {
  // Show settings prompt
}
```

---

## Full Barcode Lookup Flow (End to End)

```
User scans barcode
       │
       ▼
Client extracts barcode string (e.g. "0123456789012")
       │
       ▼
GET /api/v1/food-items/barcode/{barcode}
       │
       ▼
FoodItemServiceImpl.findByBarcode()
       │
       ├─── foodItemRepository.findByBarcode() ──► HIT → return FoodItemResponse
       │
       └─── MISS
               │
               ▼
       openFoodFactsService.lookupByBarcode()
               │
               ├─── Status 0 or null ──► throw ResourceNotFoundException (404)
               │
               └─── Status 1 (found)
                       │
                       ▼
               foodItemRepository.save(newItem)  ← persist for future lookups
                       │
                       ▼
               return FoodItemResponse
```

---

## Things That Will Go Wrong (Handle These)

**Open Food Facts has no data for this barcode:** Return 404. The client should fall back to manual name search. Do not show a crash — show a message like "Product not found. Search by name instead."

**Open Food Facts returns a product with null nutriments:** Your mapper must handle nulls. Default every missing nutritional field to `BigDecimal.ZERO` and flag the response with a `dataQuality: "incomplete"` field so the frontend can warn the user the data may be wrong.

**Open Food Facts is down or timing out:** Your `RestTemplate` has a 5-second timeout. After timeout, `lookupByBarcode()` returns `Optional.empty()` and you throw 404. Log the failure. Do not propagate a 503 to the client — from the client's perspective, the food item just wasn't found.

**User scans the same barcode twice quickly:** The second request hits your DB (it was persisted on the first request) and returns instantly. No double-call to Open Food Facts.

**Camera permission denied:** Handle this gracefully on both web and mobile. Show a fallback UI that lets users type the barcode manually. A text input that calls the same endpoint works fine.

---

## DEVLOG Entry for This Step

After completing implementation, add to `DEVLOG.md`:

```
## Step 16A-E — Barcode Scanner + Open Food Facts Fallback

Built the barcode lookup chain: check local DB → fallback to Open Food Facts API → persist on miss.
Key decisions:
- RestTemplate timeout set to 5s read / 3s connect — external calls must be bounded
- Null nutritional fields from OFF defaulted to BigDecimal.ZERO — their data quality varies widely
- Auto-persist on external hit — DB self-populates from user activity over time
- Web client: @zxing/library via getUserMedia (requires HTTPS)
- Mobile: react-native-vision-camera + vision-camera-code-scanner (EAN-13, UPC-A/E)

Gotcha: getUserMedia does not work on http:// in Safari. Tested on deployed HTTPS URL only.
```

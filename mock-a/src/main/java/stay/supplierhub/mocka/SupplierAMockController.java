package stay.supplierhub.mocka;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupplierAMockController {

    private static final String DEMO_API_KEY = "demo-a-key";
    private static final int MAX_HOTEL_CODES = 50;
    private static final int MAX_EXTRA_PROPERTIES = 9999;
    private static final Duration NO_RESPONSE_HOLD = Duration.ofSeconds(90);
    private static final int[] DELUXE_TWIN_REMAINING = {3, 1, 5};
    private static final long[][] DELUXE_TWIN_RATES = {{120000L, 12000L}, {150000L, 15000L}, {120000L, 12000L}};
    private static final int[] STANDARD_DOUBLE_REMAINING = {2, 0, 4};
    private static final long[][] STANDARD_DOUBLE_RATES = {{100000L, 10000L}, {110000L, 11000L}, {100000L, 10000L}};

    private final SearchMode mode;
    private final Duration delay;
    private final CatalogMode catalogMode;
    private final Set<String> faultCodes;
    private final List<Hotel> hotels;
    private final Map<String, Hotel> hotelsByCode;

    public SupplierAMockController(
            @Value("${mock.mode:normal}") String mode,
            @Value("${mock.delay:2500ms}") Duration delay,
            @Value("${mock.catalog-mode:normal}") String catalogMode,
            @Value("${mock.extra-properties:0}") int extraProperties,
            @Value("${mock.fault-codes:}") String faultCodes) {
        if (extraProperties < 0 || extraProperties > MAX_EXTRA_PROPERTIES) {
            throw new IllegalArgumentException("mock.extra-properties must be 0.." + MAX_EXTRA_PROPERTIES);
        }
        this.mode = SearchMode.from(mode);
        this.delay = delay;
        this.catalogMode = CatalogMode.from(catalogMode);
        this.faultCodes = splitCodes(faultCodes).stream().collect(Collectors.toUnmodifiableSet());
        this.hotels = buildHotels(extraProperties);
        this.hotelsByCode = hotels.stream().collect(Collectors.toMap(Hotel::hotelCode, Function.identity()));
    }

    @GetMapping("/a/v1/hotels")
    public ResponseEntity<?> hotels(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        ResponseEntity<ErrorBody> unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        if (catalogMode == CatalogMode.OUTAGE) {
            return serviceUnavailable();
        }
        return ResponseEntity.ok(new HotelsResponse(hotels));
    }

    @GetMapping("/a/v1/availability")
    public ResponseEntity<?> availability(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam String hotelCodes,
            @RequestParam LocalDate checkIn,
            @RequestParam LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam int children) {
        ResponseEntity<ErrorBody> unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        if (!checkOut.isAfter(checkIn)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorBody("INVALID_DATE_RANGE", "checkOut must be after checkIn"));
        }
        List<String> requestedCodes = splitCodes(hotelCodes);
        if (requestedCodes.size() > MAX_HOTEL_CODES) {
            return ResponseEntity.badRequest()
                    .body(new ErrorBody("TOO_MANY_HOTEL_CODES", "hotelCodes must be at most " + MAX_HOTEL_CODES));
        }
        if (faultApplies(requestedCodes)) {
            try {
                switch (mode) {
                    case OUTAGE -> {
                        return serviceUnavailable();
                    }
                    case NO_RESPONSE -> Thread.sleep(NO_RESPONSE_HOLD);
                    case DELAY -> Thread.sleep(delay);
                    case NORMAL -> {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return serviceUnavailable();
            }
        }
        int occupancy = adults + children;
        List<LocalDate> nights = checkIn.datesUntil(checkOut).toList();
        List<AvailabilityItem> items = new ArrayList<>();
        for (String hotelCode : requestedCodes) {
            Hotel hotel = hotelsByCode.get(hotelCode);
            if (hotel == null) {
                continue;
            }
            for (RoomType roomType : hotel.roomTypes()) {
                if (roomType.maxOccupancy() < occupancy) {
                    continue;
                }
                items.add(toAvailabilityItem(hotel, roomType, nights));
            }
        }
        return ResponseEntity.ok(new AvailabilityResponse(List.copyOf(items)));
    }

    private boolean faultApplies(List<String> requestedCodes) {
        if (mode == SearchMode.NORMAL) {
            return false;
        }
        return faultCodes.isEmpty() || !Collections.disjoint(faultCodes, requestedCodes);
    }

    private static List<Hotel> buildHotels(int extraProperties) {
        List<Hotel> hotels = new ArrayList<>();
        hotels.add(new Hotel(
                "A-10023",
                "Riverside Hotel Seoul",
                List.of(new RoomType("DLX-TWN", "Deluxe Twin", 2))));
        hotels.add(new Hotel(
                "A-10044",
                "Namsan Garden Stay",
                List.of(new RoomType("STD-DBL", "Standard Double", 2))));
        for (int i = 1; i <= extraProperties; i++) {
            String code = "A-2%04d".formatted(i);
            hotels.add(new Hotel(code, "Demo Hotel " + code, List.of(new RoomType("DLX-TWN", "Deluxe Twin", 2))));
        }
        return List.copyOf(hotels);
    }

    private static List<String> splitCodes(String codes) {
        return Arrays.stream(codes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
    }

    private static AvailabilityItem toAvailabilityItem(Hotel hotel, RoomType roomType, List<LocalDate> nights) {
        int[] remainingPattern =
                "DLX-TWN".equals(roomType.roomTypeCode()) ? DELUXE_TWIN_REMAINING : STANDARD_DOUBLE_REMAINING;
        long[][] ratePattern = "DLX-TWN".equals(roomType.roomTypeCode()) ? DELUXE_TWIN_RATES : STANDARD_DOUBLE_RATES;
        List<DailyRate> dailyRates = new ArrayList<>(nights.size());
        for (int i = 0; i < nights.size(); i++) {
            int slot = i % remainingPattern.length;
            dailyRates.add(new DailyRate(
                    nights.get(i).toString(),
                    remainingPattern[slot],
                    ratePattern[slot][0],
                    ratePattern[slot][1]));
        }
        return new AvailabilityItem(
                hotel.hotelCode(),
                hotel.hotelName(),
                roomType.roomTypeCode(),
                roomType.roomTypeName(),
                roomType.maxOccupancy(),
                false,
                "KRW",
                List.copyOf(dailyRates));
    }

    private static ResponseEntity<ErrorBody> unauthorized(String apiKey) {
        if (DEMO_API_KEY.equals(apiKey)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorBody("UNAUTHORIZED", "invalid api key"));
    }

    private static ResponseEntity<ErrorBody> serviceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorBody("SERVICE_UNAVAILABLE", "supplier temporarily unavailable"));
    }

    private enum SearchMode {
        NORMAL("normal"),
        OUTAGE("outage"),
        NO_RESPONSE("no-response"),
        DELAY("delay");

        private final String value;

        SearchMode(String value) {
            this.value = value;
        }

        static SearchMode from(String value) {
            return Arrays.stream(values())
                    .filter(mode -> mode.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "mock.mode must be normal, outage, no-response or delay: " + value));
        }
    }

    private enum CatalogMode {
        NORMAL("normal"),
        OUTAGE("outage");

        private final String value;

        CatalogMode(String value) {
            this.value = value;
        }

        static CatalogMode from(String value) {
            return Arrays.stream(values())
                    .filter(mode -> mode.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "mock.catalog-mode must be normal or outage: " + value));
        }
    }

    record HotelsResponse(List<Hotel> items) {}

    record Hotel(String hotelCode, String hotelName, List<RoomType> roomTypes) {}

    record RoomType(String roomTypeCode, String roomTypeName, int maxOccupancy) {}

    record AvailabilityResponse(List<AvailabilityItem> items) {}

    record AvailabilityItem(
            String hotelCode,
            String hotelName,
            String roomTypeCode,
            String roomTypeName,
            int maxOccupancy,
            boolean breakfastIncluded,
            String currency,
            List<DailyRate> dailyRates) {}

    record DailyRate(String date, int remainingRooms, long nightlyRate, long taxAmount) {}

    record ErrorBody(String error, String message) {}
}

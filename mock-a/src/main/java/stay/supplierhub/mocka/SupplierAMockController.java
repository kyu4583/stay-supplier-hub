package stay.supplierhub.mocka;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupplierAMockController {

    private static final String DEMO_API_KEY = "demo-a-key";
    private static final int[] DELUXE_TWIN_REMAINING = {3, 1, 5};
    private static final long[][] DELUXE_TWIN_RATES = {{120000L, 12000L}, {150000L, 15000L}, {120000L, 12000L}};
    private static final int[] STANDARD_DOUBLE_REMAINING = {2, 0, 4};
    private static final long[][] STANDARD_DOUBLE_RATES = {{100000L, 10000L}, {110000L, 11000L}, {100000L, 10000L}};

    private static final List<Hotel> HOTELS = List.of(
            new Hotel(
                    "A-10023",
                    "Riverside Hotel Seoul",
                    List.of(new RoomType("DLX-TWN", "Deluxe Twin", 2))),
            new Hotel(
                    "A-10044",
                    "Namsan Garden Stay",
                    List.of(new RoomType("STD-DBL", "Standard Double", 2))));

    private static final Map<String, Hotel> HOTELS_BY_CODE =
            HOTELS.stream().collect(Collectors.toMap(Hotel::hotelCode, Function.identity()));

    @GetMapping("/a/v1/hotels")
    public ResponseEntity<?> hotels(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        ResponseEntity<ErrorBody> unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        return ResponseEntity.ok(new HotelsResponse(HOTELS));
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
        List<String> requestedCodes = Arrays.stream(hotelCodes.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
        int occupancy = adults + children;
        List<LocalDate> nights = checkIn.datesUntil(checkOut).toList();
        List<AvailabilityItem> items = new ArrayList<>();
        for (String hotelCode : requestedCodes) {
            Hotel hotel = HOTELS_BY_CODE.get(hotelCode);
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

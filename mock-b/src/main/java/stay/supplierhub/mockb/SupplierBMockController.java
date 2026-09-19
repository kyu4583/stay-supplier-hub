package stay.supplierhub.mockb;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupplierBMockController {

    private static final String DEMO_API_KEY = "demo-b-key";
    private static final int[] DELUXE_TWIN_REMAINING = {3, 1, 5};

    private static final List<Property> PROPERTIES = List.of(
            new Property(
                    "B77120",
                    "Riverside Hotel Seoul",
                    List.of(new Room("R-401", "Deluxe Twin Room", 2))));

    private static final Map<String, Property> PROPERTIES_BY_ID =
            PROPERTIES.stream().collect(Collectors.toMap(Property::propertyId, Function.identity()));

    @GetMapping("/b/api/properties")
    public ResponseEntity<Envelope> properties(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        Envelope unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return ResponseEntity.ok(unauthorized);
        }
        return ResponseEntity.ok(success(new PropertiesData(PROPERTIES)));
    }

    @GetMapping("/b/api/search")
    public ResponseEntity<Envelope> search(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam String propertyIds,
            @RequestParam LocalDate checkIn,
            @RequestParam LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam int children) {
        Envelope unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return ResponseEntity.ok(unauthorized);
        }
        if (!checkOut.isAfter(checkIn)) {
            return ResponseEntity.ok(new Envelope("E400", "INVALID_DATE_RANGE", null));
        }
        List<String> requestedIds = Arrays.stream(propertyIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        int occupancy = adults + children;
        List<LocalDate> nights = checkIn.datesUntil(checkOut).toList();
        List<SearchItem> items = new ArrayList<>();
        for (String propertyId : requestedIds) {
            Property property = PROPERTIES_BY_ID.get(propertyId);
            if (property == null) {
                continue;
            }
            for (Room room : property.rooms()) {
                if (room.maxOccupancy() < occupancy) {
                    continue;
                }
                items.add(toSearchItem(property, room, nights));
            }
        }
        return ResponseEntity.ok(success(new SearchData(List.copyOf(items))));
    }

    private static SearchItem toSearchItem(Property property, Room room, List<LocalDate> nights) {
        List<Inventory> inventory = new ArrayList<>(nights.size());
        for (int i = 0; i < nights.size(); i++) {
            int slot = i % DELUXE_TWIN_REMAINING.length;
            inventory.add(new Inventory(nights.get(i).toString(), DELUXE_TWIN_REMAINING[slot]));
        }
        return new SearchItem(
                property.propertyId(),
                property.propertyName(),
                room.roomId(),
                room.roomName(),
                room.maxOccupancy(),
                true,
                "KRW",
                452000L,
                true,
                List.copyOf(inventory));
    }

    private static Envelope unauthorized(String apiKey) {
        if (DEMO_API_KEY.equals(apiKey)) {
            return null;
        }
        return new Envelope("E401", "UNAUTHORIZED", null);
    }

    private static Envelope success(Object data) {
        return new Envelope("0000", "SUCCESS", data);
    }

    record Envelope(String resultCode, String resultMessage, Object data) {}

    record PropertiesData(List<Property> items) {}

    record Property(String propertyId, String propertyName, List<Room> rooms) {}

    record Room(String roomId, String roomName, int maxOccupancy) {}

    record SearchData(List<SearchItem> items) {}

    record SearchItem(
            String propertyId,
            String propertyName,
            String roomId,
            String roomName,
            int maxOccupancy,
            boolean breakfastIncluded,
            String currency,
            long totalPrice,
            boolean taxIncluded,
            List<Inventory> inventory) {}

    record Inventory(String date, int remainingRooms) {}
}

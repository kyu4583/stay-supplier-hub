package stay.supplierhub.mockb;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupplierBMockController {

    private static final String DEMO_API_KEY = "demo-b-key";
    private static final int MAX_PROPERTY_IDS = 50;
    private static final int MAX_EXTRA_PROPERTIES = 9999;
    private static final Duration NO_RESPONSE_HOLD = Duration.ofSeconds(90);
    private static final int[] DELUXE_TWIN_REMAINING = {3, 1, 5};

    private final SearchMode mode;
    private final Duration delay;
    private final CatalogMode catalogMode;
    private final Set<String> faultCodes;
    private final List<Property> properties;
    private final Map<String, Property> propertiesById;

    public SupplierBMockController(
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
        this.faultCodes = splitIds(faultCodes).stream().collect(Collectors.toUnmodifiableSet());
        this.properties = buildProperties(extraProperties);
        this.propertiesById =
                properties.stream().collect(Collectors.toMap(Property::propertyId, Function.identity()));
    }

    @GetMapping("/b/api/properties")
    public ResponseEntity<Envelope> properties(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        Envelope unauthorized = unauthorized(apiKey);
        if (unauthorized != null) {
            return ResponseEntity.ok(unauthorized);
        }
        if (catalogMode == CatalogMode.OUTAGE) {
            return ResponseEntity.ok(temporarilyUnavailable());
        }
        return ResponseEntity.ok(success(new PropertiesData(properties)));
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
        List<String> requestedIds = splitIds(propertyIds);
        if (requestedIds.size() > MAX_PROPERTY_IDS) {
            return ResponseEntity.ok(new Envelope(
                    "E400",
                    "TOO_MANY_PROPERTY_IDS",
                    null));
        }
        if (faultApplies(requestedIds)) {
            try {
                switch (mode) {
                    case OUTAGE -> {
                        return ResponseEntity.ok(temporarilyUnavailable());
                    }
                    case NO_RESPONSE -> Thread.sleep(NO_RESPONSE_HOLD);
                    case DELAY -> Thread.sleep(delay);
                    case NORMAL -> {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.ok(temporarilyUnavailable());
            }
        }
        int occupancy = adults + children;
        List<LocalDate> nights = checkIn.datesUntil(checkOut).toList();
        List<SearchItem> items = new ArrayList<>();
        for (String propertyId : requestedIds) {
            Property property = propertiesById.get(propertyId);
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

    private boolean faultApplies(List<String> requestedIds) {
        if (mode == SearchMode.NORMAL) {
            return false;
        }
        return faultCodes.isEmpty() || !Collections.disjoint(faultCodes, requestedIds);
    }

    private static List<Property> buildProperties(int extraProperties) {
        List<Property> properties = new ArrayList<>();
        properties.add(new Property(
                "B77120",
                "Riverside Hotel Seoul",
                List.of(new Room("R-401", "Deluxe Twin Room", 2))));
        for (int i = 1; i <= extraProperties; i++) {
            String id = "B9%04d".formatted(i);
            properties.add(new Property(id, "Demo Stay " + id, List.of(new Room("R-401", "Deluxe Twin Room", 2))));
        }
        return List.copyOf(properties);
    }

    private static List<String> splitIds(String ids) {
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
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

    private static Envelope temporarilyUnavailable() {
        return new Envelope(
                "E503",
                "TEMPORARILY_UNAVAILABLE",
                null);
    }

    private static Envelope success(Object data) {
        return new Envelope("0000", "SUCCESS", data);
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

package stay.supplierhub.supplier.b;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import stay.supplierhub.search.ChunkedCalls;
import stay.supplierhub.search.SupplierContracts.AvailabilityQuery;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.ChunkFailure;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.RoomTypeKey;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;
import stay.supplierhub.search.SupplierContracts.UnmappedRoomType;
import tools.jackson.databind.json.JsonMapper;

@ConfigurationProperties(prefix = "stay.supplier.b")
record SupplierBProperties(
        String baseUrl,
        String apiKey,
        int maxCodesPerCall,
        Duration responseTimeout,
        Duration connectTimeout,
        Duration pendingAcquireTimeout,
        int maxConnections,
        int maxConcurrentCalls) {

    SupplierBProperties {
        requirePositive("max-codes-per-call", maxCodesPerCall);
        requirePositive("max-connections", maxConnections);
        requirePositive("max-concurrent-calls", maxConcurrentCalls);
    }

    private static void requirePositive(String key, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("stay.supplier.b." + key + " must be positive: " + value);
        }
    }
}

record SupplierBPropertiesEnvelope(String resultCode, String resultMessage, SupplierBPropertiesData data) {}

record SupplierBPropertiesData(List<SupplierBProperty> items) {}

record SupplierBProperty(String propertyId, String propertyName, List<SupplierBRoom> rooms) {}

record SupplierBRoom(String roomId, String roomName, int maxOccupancy) {}

record SupplierBSearchEnvelope(String resultCode, String resultMessage, SupplierBSearchData data) {}

record SupplierBSearchData(List<SupplierBSearchItem> items) {}

record SupplierBSearchItem(
        String propertyId,
        String propertyName,
        String roomId,
        String roomName,
        int maxOccupancy,
        boolean breakfastIncluded,
        String currency,
        Long totalPrice,
        Boolean taxIncluded,
        List<SupplierBInventory> inventory) {}

record SupplierBInventory(String date, Integer remainingRooms) {}

final class SupplierBMapper {

    SupplierCatalog toCatalog(SupplierBPropertiesEnvelope response) {
        List<SupplierBProperty> items =
                response.data() == null || response.data().items() == null
                        ? List.of()
                        : response.data().items();
        List<CatalogProperty> properties = items.stream()
                .map(property -> new CatalogProperty(
                        property.propertyId(),
                        property.propertyName(),
                        (property.rooms() == null ? List.<SupplierBRoom>of() : property.rooms())
                                .stream()
                                .map(room -> new CatalogRoomType(
                                        room.roomId(), room.roomName(), room.maxOccupancy()))
                                .toList()))
                .toList();
        return new SupplierCatalog(new SupplierId("B"), properties);
    }

    SupplierSearchResult toSearchResult(SupplierBSearchEnvelope response, AvailabilityQuery query) {
        Set<LocalDate> expectedNights =
                query.checkIn().datesUntil(query.checkOut()).collect(Collectors.toCollection(LinkedHashSet::new));
        List<SupplierBSearchItem> items = response.data() == null || response.data().items() == null
                ? List.of()
                : response.data().items();
        Set<String> requestedProperties = new LinkedHashSet<>(query.hotelCodes());
        Set<RoomTypeKey> knownRoomTypes =
                query.knownRoomTypes() == null ? Set.of() : query.knownRoomTypes();
        boolean checkMapping = !knownRoomTypes.isEmpty();
        int requestedGuests = query.adults() + query.children();

        Map<String, Integer> keyCounts = new LinkedHashMap<>();
        for (SupplierBSearchItem item : items) {
            keyCounts.merge(itemKey(item), 1, Integer::sum);
        }
        Set<String> duplicateKeys = keyCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<RoomOffer> offers = new ArrayList<>();
        List<UnmappedRoomType> unmapped = new ArrayList<>();
        for (SupplierBSearchItem item : items) {
            String key = itemKey(item);
            if (duplicateKeys.contains(key)) {
                continue;
            }
            if (item.propertyId() == null || !requestedProperties.contains(item.propertyId())) {
                continue;
            }
            if (checkMapping && !knownRoomTypes.contains(new RoomTypeKey(item.propertyId(), item.roomId()))) {
                unmapped.add(new UnmappedRoomType(item.propertyId(), item.roomId()));
                continue;
            }
            toOffer(item, expectedNights, requestedGuests).ifPresent(offers::add);
        }
        List<ChunkFailure> failures = items.isEmpty() || !offers.isEmpty()
                ? List.of()
                : List.of(new ChunkFailure(new SupplierId("B"), "INVALID_RESPONSE"));
        return new SupplierSearchResult(
                new SupplierId("B"), List.copyOf(offers), failures, List.copyOf(unmapped));
    }

    private Optional<RoomOffer> toOffer(
            SupplierBSearchItem item, Set<LocalDate> expectedNights, int requestedGuests) {
        if (item.maxOccupancy() < requestedGuests) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(item.taxIncluded())) {
            return Optional.empty();
        }
        if (item.totalPrice() == null || item.totalPrice() < 0) {
            return Optional.empty();
        }
        if (!knownCurrency(item.currency())) {
            return Optional.empty();
        }
        if (item.inventory() == null) {
            return Optional.empty();
        }
        Map<LocalDate, Integer> byDate = new LinkedHashMap<>();
        for (SupplierBInventory day : item.inventory()) {
            if (day == null || day.date() == null || day.remainingRooms() == null) {
                return Optional.empty();
            }
            LocalDate date;
            try {
                date = LocalDate.parse(day.date());
            } catch (DateTimeParseException ex) {
                return Optional.empty();
            }
            if (byDate.containsKey(date)) {
                return Optional.empty();
            }
            if (day.remainingRooms() < 0) {
                return Optional.empty();
            }
            byDate.put(date, day.remainingRooms());
        }
        if (!expectedNights.equals(byDate.keySet())) {
            return Optional.empty();
        }
        List<Integer> remainingRooms = new ArrayList<>();
        for (LocalDate night : expectedNights) {
            remainingRooms.add(byDate.get(night));
        }
        return Optional.of(new RoomOffer(
                item.propertyId(),
                item.roomId(),
                item.propertyName(),
                item.roomName(),
                item.maxOccupancy(),
                item.breakfastIncluded(),
                item.totalPrice(),
                item.currency(),
                List.copyOf(remainingRooms)));
    }

    private static String itemKey(SupplierBSearchItem item) {
        String propertyId = item.propertyId() == null ? "" : item.propertyId();
        String roomId = item.roomId() == null ? "" : item.roomId();
        return propertyId + '\0' + roomId;
    }

    private static boolean knownCurrency(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}

@Configuration
class SupplierBWebClientConfig {

    @Bean
    WebClient supplierBWebClient(SupplierBProperties properties) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("supplier-b")
                .maxConnections(properties.maxConnections())
                .pendingAcquireTimeout(properties.pendingAcquireTimeout())
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(properties.responseTimeout())
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()));
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}

@Component
public class SupplierBAdapter implements SupplierCatalogPort, SupplierAvailabilityPort {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .changeDefaultVisibility(visibility -> visibility
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withCreatorVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withSetterVisibility(JsonAutoDetect.Visibility.NONE))
            .findAndAddModules()
            .build();

    private final SupplierBProperties properties;
    private final WebClient webClient;
    private final SupplierBMapper mapper = new SupplierBMapper();

    public SupplierBAdapter(
            SupplierBProperties properties, @Qualifier("supplierBWebClient") WebClient supplierBWebClient) {
        this.properties = properties;
        this.webClient = supplierBWebClient;
    }

    @Override
    public SupplierId supplierId() {
        return new SupplierId("B");
    }

    @Override
    public Mono<SupplierCatalog> fetchCatalog() {
        return webClient
                .get()
                .uri("/b/api/properties")
                .header("X-Api-Key", properties.apiKey())
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(json -> {
                            SupplierBPropertiesEnvelope parsed;
                            try {
                                parsed = JSON_MAPPER.readValue(json, SupplierBPropertiesEnvelope.class);
                            } catch (RuntimeException ex) {
                                return Mono.error(ex);
                            }
                            if (!response.statusCode().is2xxSuccessful()) {
                                return Mono.error(new IllegalStateException(
                                        "supplier B properties failed: " + response.statusCode()));
                            }
                            if (!isSuccess(parsed) || parsed.data().items() == null) {
                                return Mono.error(new IllegalStateException("supplier B properties payload is invalid"));
                            }
                            return Mono.just(mapper.toCatalog(parsed));
                        }));
    }

    @Override
    public Mono<SupplierSearchResult> fetchAvailability(AvailabilityQuery query) {
        return ChunkedCalls.collect(
                supplierId(),
                query.hotelCodes(),
                properties.maxCodesPerCall(),
                properties.maxConcurrentCalls(),
                query.budget(),
                chunk -> fetchChunk(query.withHotelCodes(chunk)));
    }

    private Mono<SupplierSearchResult> fetchChunk(AvailabilityQuery query) {
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/b/api/search")
                        .queryParam("propertyIds", String.join(",", query.hotelCodes()))
                        .queryParam("checkIn", query.checkIn())
                        .queryParam("checkOut", query.checkOut())
                        .queryParam("adults", query.adults())
                        .queryParam("children", query.children())
                        .build())
                .header("X-Api-Key", properties.apiKey())
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(json -> {
                            SupplierBSearchEnvelope parsed;
                            try {
                                parsed = JSON_MAPPER.readValue(json, SupplierBSearchEnvelope.class);
                            } catch (RuntimeException ex) {
                                return failed("PARSE_ERROR");
                            }
                            if (!response.statusCode().is2xxSuccessful()) {
                                return failed("HTTP_" + response.statusCode().value());
                            }
                            if (!isSuccess(parsed) || parsed.data().items() == null) {
                                return failed("INVALID_RESPONSE");
                            }
                            return Mono.just(mapper.toSearchResult(parsed, query));
                        }))
                .onErrorResume(ex -> failed(ex.getClass().getSimpleName()));
    }

    private Mono<SupplierSearchResult> failed(String reason) {
        return Mono.just(new SupplierSearchResult(
                supplierId(), List.of(), List.of(new ChunkFailure(supplierId(), reason))));
    }

    private static boolean isSuccess(SupplierBPropertiesEnvelope envelope) {
        return envelope != null && "0000".equals(envelope.resultCode()) && envelope.data() != null;
    }

    private static boolean isSuccess(SupplierBSearchEnvelope envelope) {
        return envelope != null && "0000".equals(envelope.resultCode()) && envelope.data() != null;
    }
}

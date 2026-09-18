package stay.supplierhub.supplier.a;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
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
import stay.supplierhub.search.SupplierContracts.AvailabilityQuery;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;
import tools.jackson.databind.json.JsonMapper;

@ConfigurationProperties(prefix = "stay.supplier.a")
record SupplierAProperties(
        String baseUrl,
        String apiKey,
        int maxCodesPerCall,
        Duration responseTimeout,
        Duration connectTimeout,
        Duration pendingAcquireTimeout,
        int maxConnections) {}

record SupplierAHotelsResponse(List<SupplierAHotel> items) {}

record SupplierAHotel(String hotelCode, String hotelName, List<SupplierARoomType> roomTypes) {}

record SupplierARoomType(String roomTypeCode, String roomTypeName, int maxOccupancy) {}

record SupplierAAvailabilityResponse(List<SupplierAAvailabilityItem> items) {}

record SupplierAAvailabilityItem(
        String hotelCode,
        String hotelName,
        String roomTypeCode,
        String roomTypeName,
        int maxOccupancy,
        boolean breakfastIncluded,
        String currency,
        List<SupplierADailyRate> dailyRates) {}

record SupplierADailyRate(String date, int remainingRooms, long nightlyRate, long taxAmount) {}

final class SupplierAMapper {

    SupplierCatalog toCatalog(SupplierAHotelsResponse response) {
        List<CatalogProperty> properties = response.items().stream()
                .map(hotel -> new CatalogProperty(
                        hotel.hotelCode(),
                        hotel.hotelName(),
                        hotel.roomTypes().stream()
                                .map(roomType -> new CatalogRoomType(
                                        roomType.roomTypeCode(), roomType.roomTypeName(), roomType.maxOccupancy()))
                                .toList()))
                .toList();
        return new SupplierCatalog(new SupplierId("A"), properties);
    }

    SupplierSearchResult toSearchResult(SupplierAAvailabilityResponse response, AvailabilityQuery query) {
        Set<LocalDate> expectedNights =
                query.checkIn().datesUntil(query.checkOut()).collect(Collectors.toCollection(LinkedHashSet::new));
        List<RoomOffer> offers = new ArrayList<>();
        for (SupplierAAvailabilityItem item : response.items()) {
            toOffer(item, expectedNights).ifPresent(offers::add);
        }
        return new SupplierSearchResult(new SupplierId("A"), List.copyOf(offers), List.of());
    }

    private Optional<RoomOffer> toOffer(SupplierAAvailabilityItem item, Set<LocalDate> expectedNights) {
        if (item.dailyRates() == null) {
            return Optional.empty();
        }
        Map<LocalDate, SupplierADailyRate> byDate = new LinkedHashMap<>();
        for (SupplierADailyRate rate : item.dailyRates()) {
            byDate.put(LocalDate.parse(rate.date()), rate);
        }
        if (!expectedNights.equals(byDate.keySet())) {
            return Optional.empty();
        }
        long totalPrice = 0L;
        List<Integer> remainingRooms = new ArrayList<>();
        for (LocalDate night : expectedNights) {
            SupplierADailyRate rate = byDate.get(night);
            totalPrice += rate.nightlyRate() + rate.taxAmount();
            remainingRooms.add(rate.remainingRooms());
        }
        return Optional.of(new RoomOffer(
                item.hotelCode(),
                item.roomTypeCode(),
                item.hotelName(),
                item.roomTypeName(),
                item.maxOccupancy(),
                item.breakfastIncluded(),
                totalPrice,
                item.currency(),
                List.copyOf(remainingRooms)));
    }
}

@Configuration
class SupplierAWebClientConfig {

    @Bean
    WebClient supplierAWebClient(SupplierAProperties properties) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("supplier-a")
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
public class SupplierAAdapter implements SupplierCatalogPort, SupplierAvailabilityPort {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .changeDefaultVisibility(visibility -> visibility
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withCreatorVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withSetterVisibility(JsonAutoDetect.Visibility.NONE))
            .findAndAddModules()
            .build();

    private final SupplierAProperties properties;
    private final WebClient webClient;
    private final SupplierAMapper mapper = new SupplierAMapper();

    public SupplierAAdapter(
            SupplierAProperties properties, @Qualifier("supplierAWebClient") WebClient supplierAWebClient) {
        this.properties = properties;
        this.webClient = supplierAWebClient;
    }

    @Override
    public SupplierId supplierId() {
        return new SupplierId("A");
    }

    @Override
    public Mono<SupplierCatalog> fetchCatalog() {
        return webClient
                .get()
                .uri("/a/v1/hotels")
                .header("X-Api-Key", properties.apiKey())
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(json -> {
                            SupplierAHotelsResponse parsed;
                            try {
                                parsed = JSON_MAPPER.readValue(json, SupplierAHotelsResponse.class);
                            } catch (RuntimeException ex) {
                                return Mono.error(ex);
                            }
                            if (!response.statusCode().is2xxSuccessful()) {
                                return Mono.error(new IllegalStateException(
                                        "supplier A hotels failed: " + response.statusCode()));
                            }
                            if (parsed.items() == null) {
                                return Mono.error(new IllegalStateException("supplier A hotels payload is invalid"));
                            }
                            return Mono.just(mapper.toCatalog(parsed));
                        }));
    }

    @Override
    public Mono<SupplierSearchResult> fetchAvailability(AvailabilityQuery query) {
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/a/v1/availability")
                        .queryParam("hotelCodes", String.join(",", query.hotelCodes()))
                        .queryParam("checkIn", query.checkIn())
                        .queryParam("checkOut", query.checkOut())
                        .queryParam("adults", query.adults())
                        .queryParam("children", query.children())
                        .build())
                .header("X-Api-Key", properties.apiKey())
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(json -> {
                            SupplierAAvailabilityResponse parsed;
                            try {
                                parsed = JSON_MAPPER.readValue(json, SupplierAAvailabilityResponse.class);
                            } catch (RuntimeException ex) {
                                return Mono.error(ex);
                            }
                            if (!response.statusCode().is2xxSuccessful()) {
                                return Mono.error(new IllegalStateException(
                                        "supplier A availability failed: " + response.statusCode()));
                            }
                            if (parsed.items() == null) {
                                return Mono.error(
                                        new IllegalStateException("supplier A availability payload is invalid"));
                            }
                            return Mono.just(mapper.toSearchResult(parsed, query));
                        }));
    }
}

package stay.supplierhub.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import stay.supplierhub.api.StaySearchRequest;
import stay.supplierhub.api.StaySearchResponse;
import stay.supplierhub.api.StaySearchResponse.FailedSupplierResponse;
import stay.supplierhub.api.StaySearchResponse.OfferResponse;
import stay.supplierhub.api.StaySearchResponse.PropertyResponse;
import stay.supplierhub.api.StaySearchResponse.RoomTypeResponse;
import stay.supplierhub.mapping.MappingStore.MappedOfferTarget;
import stay.supplierhub.mapping.MappingStore.MappingSnapshot;
import stay.supplierhub.mapping.MappingStore.MappingSnapshotHolder;
import stay.supplierhub.mapping.MappingSync.PropertyListSynchronizer;
import stay.supplierhub.search.SupplierContracts.AvailabilityQuery;
import stay.supplierhub.search.SupplierContracts.ChunkFailure;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.StaySearchUnavailableException;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;
import stay.supplierhub.search.SupplierContracts.UnmappedRoomType;

public interface StaySearchService {

    StaySearchResponse search(StaySearchRequest request);
}

@ConfigurationProperties(prefix = "stay.search")
record SearchBudgetProperties(Duration budget) {

    SearchBudgetProperties {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("stay.search.budget must be positive: " + budget);
        }
    }
}

@Service
class DefaultStaySearchService implements StaySearchService {

    private static final Logger log = LoggerFactory.getLogger(DefaultStaySearchService.class);
    private static final Duration BUDGET_GRACE = Duration.ofMillis(250);

    private final MappingSnapshotHolder snapshotHolder;
    private final List<SupplierAvailabilityPort> availabilityPorts;
    private final PropertyListSynchronizer synchronizer;
    private final Duration budget;

    DefaultStaySearchService(
            MappingSnapshotHolder snapshotHolder,
            List<SupplierAvailabilityPort> availabilityPorts,
            PropertyListSynchronizer synchronizer,
            SearchBudgetProperties budgetProperties) {
        this.snapshotHolder = snapshotHolder;
        this.availabilityPorts = List.copyOf(availabilityPorts);
        this.synchronizer = synchronizer;
        this.budget = budgetProperties.budget();
    }

    @Override
    public StaySearchResponse search(StaySearchRequest request) {
        MappingSnapshot snapshot = snapshotHolder.current();
        List<FailedSupplierResponse> unreadies = new ArrayList<>();
        List<Mono<SupplierSearchResult>> calls = new ArrayList<>();
        boolean omittedReady = false;
        for (SupplierAvailabilityPort port : availabilityPorts) {
            if (!snapshot.participates(port.supplierId())) {
                continue;
            }
            if (!snapshot.isReady(port.supplierId())) {
                unreadies.add(new FailedSupplierResponse(port.supplierId().value()));
                continue;
            }
            List<String> hotelCodes = snapshot.activePropertyCodes(port.supplierId());
            if (hotelCodes.isEmpty()) {
                omittedReady = true;
                continue;
            }
            calls.add(port.fetchAvailability(new AvailabilityQuery(
                            hotelCodes,
                            request.checkIn(),
                            request.checkOut(),
                            request.adults(),
                            request.children(),
                            snapshot.knownRoomTypes(port.supplierId()),
                            budget))
                    .timeout(budget.plus(BUDGET_GRACE))
                    .onErrorResume(ex -> {
                        log.warn("supplier call aborted supplier={}", port.supplierId().value(), ex);
                        return Mono.just(new SupplierSearchResult(
                                port.supplierId(),
                                List.of(),
                                List.of(new ChunkFailure(
                                        port.supplierId(),
                                        ex instanceof TimeoutException ? "BUDGET_EXCEEDED" : "UNAVAILABLE"))));
                    }));
        }

        List<SupplierSearchResult> resolved = List.of();
        if (!calls.isEmpty()) {
            List<SupplierSearchResult> results = Flux.merge(calls).collectList().block();
            resolved = results == null ? List.of() : results;
        }
        for (SupplierSearchResult result : resolved) {
            if (!result.failures().isEmpty()) {
                log.warn(
                        "supplier partial failure supplier={} reasons={}",
                        result.supplier().value(),
                        result.failures().stream().map(ChunkFailure::reason).toList());
            }
            for (UnmappedRoomType unmapped : result.unmappedRoomTypes()) {
                synchronizer.requestForUnmapped(
                        result.supplier(), unmapped.supplierPropertyCode(), unmapped.supplierRoomTypeCode());
            }
        }
        return assemble(snapshot, resolved, unreadies, omittedReady);
    }

    private StaySearchResponse assemble(
            MappingSnapshot snapshot,
            List<SupplierSearchResult> results,
            List<FailedSupplierResponse> unreadies,
            boolean omittedReady) {
        Map<Long, PropertyAcc> properties = new LinkedHashMap<>();
        List<FailedSupplierResponse> failedSuppliers = new ArrayList<>(unreadies);
        boolean anySuccessfulCall = false;
        boolean anyCallFailure = false;
        for (SupplierSearchResult result : results) {
            if (!result.failures().isEmpty()) {
                failedSuppliers.add(new FailedSupplierResponse(result.supplier().value()));
                anyCallFailure = true;
                if (!result.offers().isEmpty()) {
                    anySuccessfulCall = true;
                }
            } else {
                anySuccessfulCall = true;
            }
            for (RoomOffer offer : result.offers()) {
                java.util.Optional<MappedOfferTarget> mapped = snapshot.find(
                        result.supplier(), offer.supplierPropertyCode(), offer.supplierRoomTypeCode());
                if (mapped.isPresent()) {
                    addOffer(properties, mapped.get(), result.supplier().value(), offer);
                }
            }
        }
        if (!anySuccessfulCall && !failedSuppliers.isEmpty() && !(omittedReady && !anyCallFailure)) {
            throw new StaySearchUnavailableException(
                    failedSuppliers.stream().map(FailedSupplierResponse::supplier).toList());
        }
        List<PropertyResponse> propertyResponses = properties.values().stream()
                .map(property -> new PropertyResponse(
                        property.propertyId,
                        property.propertyName,
                        property.roomTypes.values().stream()
                                .map(room -> new RoomTypeResponse(
                                        room.roomTypeId, room.roomTypeName, room.maxOccupancy, List.copyOf(room.offers)))
                                .toList()))
                .toList();
        return new StaySearchResponse(propertyResponses, List.copyOf(failedSuppliers));
    }

    private void addOffer(
            Map<Long, PropertyAcc> properties, MappedOfferTarget mapped, String supplier, RoomOffer offer) {
        String propertyName = overlay(offer.propertyName(), mapped.propertyName());
        String roomTypeName = overlay(offer.roomTypeName(), mapped.roomTypeName());
        int maxOccupancy = offer.maxOccupancy() > 0 ? offer.maxOccupancy() : mapped.maxOccupancy();
        int availableRooms = offer.remainingRooms().stream().mapToInt(Integer::intValue).min().orElse(0);
        PropertyAcc property = properties.computeIfAbsent(
                mapped.propertyId(), id -> new PropertyAcc(id, propertyName, new LinkedHashMap<>()));
        RoomAcc room = property.roomTypes.computeIfAbsent(
                mapped.roomTypeId(), id -> new RoomAcc(id, roomTypeName, maxOccupancy, new ArrayList<>()));
        room.offers.add(new OfferResponse(
                supplier, offer.breakfastIncluded(), offer.totalPrice(), offer.currency(), availableRooms));
    }

    private static String overlay(String incoming, String fallback) {
        return incoming == null || incoming.isBlank() ? fallback : incoming;
    }

    private static final class PropertyAcc {
        private final long propertyId;
        private final String propertyName;
        private final Map<Long, RoomAcc> roomTypes;

        private PropertyAcc(long propertyId, String propertyName, Map<Long, RoomAcc> roomTypes) {
            this.propertyId = propertyId;
            this.propertyName = propertyName;
            this.roomTypes = roomTypes;
        }
    }

    private static final class RoomAcc {
        private final long roomTypeId;
        private final String roomTypeName;
        private final int maxOccupancy;
        private final List<OfferResponse> offers;

        private RoomAcc(long roomTypeId, String roomTypeName, int maxOccupancy, List<OfferResponse> offers) {
            this.roomTypeId = roomTypeId;
            this.roomTypeName = roomTypeName;
            this.maxOccupancy = maxOccupancy;
            this.offers = offers;
        }
    }
}

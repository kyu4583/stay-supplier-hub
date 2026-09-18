package stay.supplierhub.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import stay.supplierhub.search.SupplierContracts.AvailabilityQuery;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.StaySearchUnavailableException;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;

public interface StaySearchService {

    StaySearchResponse search(StaySearchRequest request);
}

@Service
class DefaultStaySearchService implements StaySearchService {

    private final MappingSnapshotHolder snapshotHolder;
    private final List<SupplierAvailabilityPort> availabilityPorts;

    DefaultStaySearchService(
            MappingSnapshotHolder snapshotHolder, List<SupplierAvailabilityPort> availabilityPorts) {
        this.snapshotHolder = snapshotHolder;
        this.availabilityPorts = List.copyOf(availabilityPorts);
    }

    @Override
    public StaySearchResponse search(StaySearchRequest request) {
        MappingSnapshot snapshot = snapshotHolder.current();
        if (!snapshot.ready()) {
            List<String> failed = availabilityPorts.stream()
                    .map(port -> port.supplierId().value())
                    .toList();
            throw new StaySearchUnavailableException(failed.isEmpty() ? List.of("A") : failed);
        }

        List<Mono<SupplierSearchResult>> calls = new ArrayList<>();
        for (SupplierAvailabilityPort port : availabilityPorts) {
            List<String> hotelCodes = snapshot.activePropertyCodes(port.supplierId());
            if (hotelCodes.isEmpty()) {
                continue;
            }
            calls.add(port.fetchAvailability(new AvailabilityQuery(
                    hotelCodes, request.checkIn(), request.checkOut(), request.adults(), request.children())));
        }
        if (calls.isEmpty()) {
            return new StaySearchResponse(List.of(), List.of());
        }

        List<SupplierSearchResult> results = Flux.merge(calls).collectList().block();
        return assemble(snapshot, results == null ? List.of() : results);
    }

    private StaySearchResponse assemble(MappingSnapshot snapshot, List<SupplierSearchResult> results) {
        Map<Long, PropertyAcc> properties = new LinkedHashMap<>();
        List<FailedSupplierResponse> failedSuppliers = new ArrayList<>();
        boolean anyOffer = false;
        for (SupplierSearchResult result : results) {
            if (!result.failures().isEmpty()) {
                failedSuppliers.add(new FailedSupplierResponse(result.supplier().value()));
            }
            for (RoomOffer offer : result.offers()) {
                snapshot.find(result.supplier(), offer.supplierPropertyCode(), offer.supplierRoomTypeCode())
                        .ifPresent(mapped -> {
                            addOffer(properties, mapped, result.supplier().value(), offer);
                        });
                anyOffer = true;
            }
        }
        if (!anyOffer && !failedSuppliers.isEmpty()) {
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

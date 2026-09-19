package stay.supplierhub.search;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import reactor.core.publisher.Mono;

public final class SupplierContracts {

    private SupplierContracts() {}

    public record SupplierId(String value) {}

    public interface SupplierCatalogPort {

        SupplierId supplierId();

        Mono<SupplierCatalog> fetchCatalog();
    }

    public interface SupplierAvailabilityPort {

        SupplierId supplierId();

        Mono<SupplierSearchResult> fetchAvailability(AvailabilityQuery query);
    }

    public record RoomTypeKey(String supplierPropertyCode, String supplierRoomTypeCode) {}

    public record UnmappedRoomType(String supplierPropertyCode, String supplierRoomTypeCode) {}

    public record AvailabilityQuery(
            List<String> hotelCodes,
            LocalDate checkIn,
            LocalDate checkOut,
            int adults,
            int children,
            Set<RoomTypeKey> knownRoomTypes,
            Duration budget) {

        public AvailabilityQuery(
                List<String> hotelCodes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {
            this(hotelCodes, checkIn, checkOut, adults, children, Set.of());
        }

        public AvailabilityQuery(
                List<String> hotelCodes,
                LocalDate checkIn,
                LocalDate checkOut,
                int adults,
                int children,
                Set<RoomTypeKey> knownRoomTypes) {
            this(hotelCodes, checkIn, checkOut, adults, children, knownRoomTypes, null);
        }

        public AvailabilityQuery withHotelCodes(List<String> codes) {
            return new AvailabilityQuery(codes, checkIn, checkOut, adults, children, knownRoomTypes, budget);
        }
    }

    public record SupplierCatalog(SupplierId supplier, List<CatalogProperty> properties) {}

    public record CatalogProperty(String supplierPropertyCode, String name, List<CatalogRoomType> roomTypes) {}

    public record CatalogRoomType(String supplierRoomTypeCode, String name, int maxOccupancy) {}

    public record RoomOffer(
            String supplierPropertyCode,
            String supplierRoomTypeCode,
            String propertyName,
            String roomTypeName,
            int maxOccupancy,
            boolean breakfastIncluded,
            long totalPrice,
            String currency,
            List<Integer> remainingRooms) {}

    public record SupplierSearchResult(
            SupplierId supplier,
            List<RoomOffer> offers,
            List<ChunkFailure> failures,
            List<UnmappedRoomType> unmappedRoomTypes) {

        public SupplierSearchResult(SupplierId supplier, List<RoomOffer> offers, List<ChunkFailure> failures) {
            this(supplier, offers, failures, List.of());
        }
    }

    public record ChunkFailure(SupplierId supplier, String reason) {}

    public static class StaySearchUnavailableException extends ErrorResponseException {

        public StaySearchUnavailableException(List<String> failedSuppliers) {
            super(HttpStatus.SERVICE_UNAVAILABLE, problem(failedSuppliers), null);
        }

        private static ProblemDetail problem(List<String> failedSuppliers) {
            ProblemDetail detail =
                    ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "stay search is unavailable");
            detail.setProperty(
                    "failedSuppliers",
                    failedSuppliers.stream().map(supplier -> Map.of("supplier", supplier)).toList());
            return detail;
        }
    }
}

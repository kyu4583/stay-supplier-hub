package stay.supplierhub.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record StaySearchResponse(
        List<PropertyResponse> properties,
        @Schema(description = "결과의 일부 또는 전부가 빠진 공급사. 없으면 빈 배열이다. 항목은 supplier 코드를 담은 객체이며 실패 사유는 담지 않는다.")
        List<FailedSupplierResponse> failedSuppliers) {

    public record PropertyResponse(
            Long propertyId, String propertyName, List<RoomTypeResponse> roomTypes) {}

    public record RoomTypeResponse(
            Long roomTypeId, String roomTypeName, int maxOccupancy, List<OfferResponse> offers) {}

    public record OfferResponse(
            String supplier,
            boolean breakfastIncluded,
            long totalPrice,
            String currency,
            @Schema(description = "그 오퍼로 요청 기간 전체를 예약할 수 있는 객실 수이다. 오퍼끼리 더할 수 없다. 0이면 요청 기간 중 어느 날 실제로 매진이라 예약할 수 없다.")
            int availableRooms) {}

    public record FailedSupplierResponse(String supplier) {}
}

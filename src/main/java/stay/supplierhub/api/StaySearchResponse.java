package stay.supplierhub.api;

import java.util.List;

public record StaySearchResponse(
        List<PropertyResponse> properties, List<FailedSupplierResponse> failedSuppliers) {

    public record PropertyResponse(
            Long propertyId, String propertyName, List<RoomTypeResponse> roomTypes) {}

    public record RoomTypeResponse(
            Long roomTypeId, String roomTypeName, int maxOccupancy, List<OfferResponse> offers) {}

    public record OfferResponse(
            String supplier,
            boolean breakfastIncluded,
            long totalPrice,
            String currency,
            int availableRooms) {}

    public record FailedSupplierResponse(String supplier) {}
}

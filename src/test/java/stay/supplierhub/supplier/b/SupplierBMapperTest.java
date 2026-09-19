package stay.supplierhub.supplier.b;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stay.supplierhub.search.SupplierContracts.AvailabilityQuery;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;

@DisplayName("공급사 B 매퍼")
class SupplierBMapperTest {

    private final SupplierBMapper mapper = new SupplierBMapper();
    private final AvailabilityQuery 삼박 =
            new AvailabilityQuery(List.of("B77120"), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 2, 0);

    @Test
    @DisplayName("일치하는 inventory와 taxIncluded true에서 totalPrice 452000과 remainingRooms [3,1,5]를 만든다")
    void 기간총액_452000_재고_3_1_5() {
        SupplierSearchResult 결과 = mapper.toSearchResult(응답(true, true, 일치하는재고()), 삼박);

        assertThat(결과.offers(), hasSize(1));
        RoomOffer 오퍼 = 결과.offers().getFirst();
        assertThat(오퍼.totalPrice(), equalTo(452000L));
        assertThat(오퍼.remainingRooms(), equalTo(List.of(3, 1, 5)));
        assertThat(오퍼.breakfastIncluded(), equalTo(true));
    }

    @Test
    @DisplayName("날짜 집합이 요청 숙박일과 다르면 그 항목을 제외한다")
    void 날짜집합_불일치는_제외한다() {
        SupplierBSearchItem 빠짐 = 항목(
                true,
                true,
                List.of(
                        new SupplierBInventory("2026-09-20", 3),
                        new SupplierBInventory("2026-09-22", 5)));
        SupplierBSearchItem 넘침 = 항목(
                true,
                true,
                List.of(
                        new SupplierBInventory("2026-09-20", 3),
                        new SupplierBInventory("2026-09-21", 1),
                        new SupplierBInventory("2026-09-22", 5),
                        new SupplierBInventory("2026-09-23", 2)));

        SupplierSearchResult 결과 =
                mapper.toSearchResult(new SupplierBSearchEnvelope("0000", "OK", new SupplierBSearchData(List.of(빠짐, 넘침))), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("taxIncluded가 false면 그 항목을 제외한다")
    void taxIncluded_false는_제외한다() {
        SupplierSearchResult 결과 = mapper.toSearchResult(응답(true, false, 일치하는재고()), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("taxIncluded가 없으면 그 항목을 제외한다")
    void taxIncluded_생략은_제외한다() {
        SupplierBSearchItem 생략 = new SupplierBSearchItem(
                "B77120",
                "Riverside Hotel Seoul",
                "R-401",
                "Deluxe Twin Room",
                2,
                true,
                "KRW",
                452000L,
                null,
                일치하는재고());

        SupplierSearchResult 결과 =
                mapper.toSearchResult(new SupplierBSearchEnvelope("0000", "OK", new SupplierBSearchData(List.of(생략))), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("remainingRooms가 0이면 오퍼를 유지한다")
    void 재고0은_유효한_오퍼다() {
        SupplierBSearchItem 재고0 = 항목(
                true,
                true,
                List.of(
                        new SupplierBInventory("2026-09-20", 0),
                        new SupplierBInventory("2026-09-21", 0),
                        new SupplierBInventory("2026-09-22", 1)));

        SupplierSearchResult 결과 =
                mapper.toSearchResult(new SupplierBSearchEnvelope("0000", "OK", new SupplierBSearchData(List.of(재고0))), 삼박);

        assertThat(결과.offers(), hasSize(1));
        assertThat(결과.offers().getFirst().remainingRooms(), equalTo(List.of(0, 0, 1)));
        assertThat(결과.failures(), empty());
    }

    @Test
    @DisplayName("조식 플래그는 복사하고 totalPrice에는 조식 항을 넣지 않는다")
    void 조식은_플래그만_복사한다() {
        SupplierSearchResult 결과 = mapper.toSearchResult(응답(true, true, 일치하는재고()), 삼박);

        assertThat(결과.offers(), hasSize(1));
        RoomOffer 오퍼 = 결과.offers().getFirst();
        assertThat(오퍼.breakfastIncluded(), equalTo(true));
        assertThat(오퍼.totalPrice(), equalTo(452000L));
    }

    private static SupplierBSearchEnvelope 응답(boolean 조식포함, Boolean 세금포함, List<SupplierBInventory> 재고) {
        return new SupplierBSearchEnvelope("0000", "OK", new SupplierBSearchData(List.of(항목(조식포함, 세금포함, 재고))));
    }

    private static SupplierBSearchItem 항목(boolean 조식포함, Boolean 세금포함, List<SupplierBInventory> 재고) {
        return new SupplierBSearchItem(
                "B77120",
                "Riverside Hotel Seoul",
                "R-401",
                "Deluxe Twin Room",
                2,
                조식포함,
                "KRW",
                452000L,
                세금포함,
                재고);
    }

    private static List<SupplierBInventory> 일치하는재고() {
        return List.of(
                new SupplierBInventory("2026-09-20", 3),
                new SupplierBInventory("2026-09-21", 1),
                new SupplierBInventory("2026-09-22", 5));
    }
}

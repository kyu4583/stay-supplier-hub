package stay.supplierhub.supplier.a;

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

@DisplayName("공급사 A 매퍼")
class SupplierAMapperTest {

    private final SupplierAMapper mapper = new SupplierAMapper();
    private final AvailabilityQuery 삼박 =
            new AvailabilityQuery(List.of("A-10023"), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 2, 0);

    @Test
    @DisplayName("숙박일마다 nightlyRate와 taxAmount를 더해 429000을 만든다")
    void 세금포함_기간총액_429000() {
        SupplierSearchResult 결과 = mapper.toSearchResult(응답(false, 일치하는요금()), 삼박);

        assertThat(결과.offers(), hasSize(1));
        RoomOffer 오퍼 = 결과.offers().getFirst();
        assertThat(오퍼.totalPrice(), equalTo(429000L));
        assertThat(오퍼.breakfastIncluded(), equalTo(false));
        assertThat(오퍼.remainingRooms(), equalTo(List.of(3, 1, 5)));
    }

    @Test
    @DisplayName("날짜 집합이 요청 숙박일과 다르면 그 항목을 제외한다")
    void 날짜집합_불일치는_제외한다() {
        SupplierAAvailabilityItem 빠짐 = 항목(
                false,
                List.of(
                        new SupplierADailyRate("2026-09-20", 3, 120000, 12000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000)));
        SupplierAAvailabilityItem 넘침 = 항목(
                false,
                List.of(
                        new SupplierADailyRate("2026-09-20", 3, 120000, 12000),
                        new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000),
                        new SupplierADailyRate("2026-09-23", 2, 100000, 10000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(빠짐, 넘침)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("조식 플래그는 복사하고 totalPrice에는 조식 항을 넣지 않는다")
    void 조식은_플래그만_복사한다() {
        SupplierSearchResult 결과 = mapper.toSearchResult(응답(true, 일치하는요금()), 삼박);

        assertThat(결과.offers(), hasSize(1));
        RoomOffer 오퍼 = 결과.offers().getFirst();
        assertThat(오퍼.breakfastIncluded(), equalTo(true));
        assertThat(오퍼.totalPrice(), equalTo(429000L));
    }

    @Test
    @DisplayName("remainingRooms가 -1이면 그 항목을 제외한다")
    void 음수_재고는_제외한다() {
        SupplierAAvailabilityItem 음수재고 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", -1, 120000, 12000),
                        new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(음수재고)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("nightlyRate가 -1이면 그 항목을 제외한다")
    void 음수_숙박요금은_제외한다() {
        SupplierAAvailabilityItem 음수요금 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", 3, -1, 12000),
                        new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(음수요금)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("taxAmount가 -1이면 그 항목을 제외한다")
    void 음수_세금은_제외한다() {
        SupplierAAvailabilityItem 음수세금 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", 3, 120000, -1),
                        new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(음수세금)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("remainingRooms가 0이면 오퍼를 유지한다")
    void 재고0은_유효한_오퍼다() {
        SupplierAAvailabilityItem 재고0 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", 0, 120000, 12000),
                        new SupplierADailyRate("2026-09-21", 0, 150000, 15000),
                        new SupplierADailyRate("2026-09-22", 1, 120000, 12000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(재고0)), 삼박);

        assertThat(결과.offers(), hasSize(1));
        assertThat(결과.offers().getFirst().remainingRooms(), equalTo(List.of(0, 0, 1)));
        assertThat(결과.failures(), empty());
    }

    @Test
    @DisplayName("ISO 4217이 아닌 통화는 제외한다")
    void 모르는_통화는_제외한다() {
        SupplierAAvailabilityItem 모르는통화 = 항목("A-10023", "DLX-TWN", 2, "FOO", 일치하는요금());

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(모르는통화)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("maxOccupancy가 요청 인원보다 작으면 제외한다")
    void 수용인원_부족은_제외한다() {
        SupplierAAvailabilityItem 인원1 = 항목("A-10023", "DLX-TWN", 1, "KRW", 일치하는요금());

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(인원1)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("같은 hotelCode와 roomTypeCode가 두 번이면 둘 다 제외한다")
    void 중복_키는_둘_다_제외한다() {
        SupplierAAvailabilityItem 첫번째 = 항목("A-10023", "DLX-TWN", 2, "KRW", 일치하는요금());
        SupplierAAvailabilityItem 두번째 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", 9, 100000, 10000),
                        new SupplierADailyRate("2026-09-21", 9, 100000, 10000),
                        new SupplierADailyRate("2026-09-22", 9, 100000, 10000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(첫번째, 두번째)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("요청 hotelCodes에 없는 숙소 코드는 제외한다")
    void 요청밖_숙소코드는_제외한다() {
        SupplierAAvailabilityItem 요청밖 = 항목("A-99999", "DLX-TWN", 2, "KRW", 일치하는요금());

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(요청밖)), 삼박);

        assertThat(결과.offers(), empty());
    }

    @Test
    @DisplayName("정상과 위반이 섞이면 정상만 남고 failures는 비다")
    void 혼합은_정상만_남긴다() {
        SupplierAAvailabilityItem 정상 = 항목("A-10023", "DLX-TWN", 2, "KRW", 일치하는요금());
        SupplierAAvailabilityItem 음수재고 = 항목(
                "A-10023",
                "STD-DBL",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", -1, 100000, 10000),
                        new SupplierADailyRate("2026-09-21", 1, 100000, 10000),
                        new SupplierADailyRate("2026-09-22", 1, 100000, 10000)));

        SupplierSearchResult 결과 =
                mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(정상, 음수재고)), 삼박);

        assertThat(결과.offers(), hasSize(1));
        assertThat(결과.offers().getFirst().supplierRoomTypeCode(), equalTo("DLX-TWN"));
        assertThat(결과.failures(), empty());
    }

    @Test
    @DisplayName("항목이 전부 제외되면 그 호출은 실패다")
    void 전항목_제외는_호출실패() {
        SupplierAAvailabilityItem 빠짐 = 항목(
                "A-10023",
                "DLX-TWN",
                2,
                "KRW",
                List.of(
                        new SupplierADailyRate("2026-09-20", 3, 120000, 12000),
                        new SupplierADailyRate("2026-09-22", 5, 120000, 12000)));

        SupplierSearchResult 결과 = mapper.toSearchResult(new SupplierAAvailabilityResponse(List.of(빠짐)), 삼박);

        assertThat(결과.offers(), empty());
        assertThat(결과.failures(), hasSize(1));
        assertThat(결과.failures().getFirst().supplier().value(), equalTo("A"));
    }

    private static SupplierAAvailabilityResponse 응답(boolean 조식포함, List<SupplierADailyRate> 요금) {
        return new SupplierAAvailabilityResponse(List.of(항목(조식포함, 요금)));
    }

    private static SupplierAAvailabilityItem 항목(boolean 조식포함, List<SupplierADailyRate> 요금) {
        return new SupplierAAvailabilityItem(
                "A-10023", "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", 2, 조식포함, "KRW", 요금);
    }

    private static SupplierAAvailabilityItem 항목(
            String hotelCode, String roomTypeCode, int maxOccupancy, String currency, List<SupplierADailyRate> 요금) {
        return new SupplierAAvailabilityItem(
                hotelCode, "Hotel", roomTypeCode, "Room", maxOccupancy, false, currency, 요금);
    }

    private static List<SupplierADailyRate> 일치하는요금() {
        return List.of(
                new SupplierADailyRate("2026-09-20", 3, 120000, 12000),
                new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                new SupplierADailyRate("2026-09-22", 5, 120000, 12000));
    }
}

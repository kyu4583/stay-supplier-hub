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

    private static SupplierAAvailabilityResponse 응답(boolean 조식포함, List<SupplierADailyRate> 요금) {
        return new SupplierAAvailabilityResponse(List.of(항목(조식포함, 요금)));
    }

    private static SupplierAAvailabilityItem 항목(boolean 조식포함, List<SupplierADailyRate> 요금) {
        return new SupplierAAvailabilityItem(
                "A-10023", "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", 2, 조식포함, "KRW", 요금);
    }

    private static List<SupplierADailyRate> 일치하는요금() {
        return List.of(
                new SupplierADailyRate("2026-09-20", 3, 120000, 12000),
                new SupplierADailyRate("2026-09-21", 1, 150000, 15000),
                new SupplierADailyRate("2026-09-22", 5, 120000, 12000));
    }
}

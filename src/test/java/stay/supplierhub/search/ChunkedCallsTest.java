package stay.supplierhub.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import stay.supplierhub.search.SupplierContracts.ChunkFailure;
import stay.supplierhub.search.SupplierContracts.RoomOffer;
import stay.supplierhub.search.SupplierContracts.SupplierId;
import stay.supplierhub.search.SupplierContracts.SupplierSearchResult;

@DisplayName("ChunkedCalls 묶음 호출 도우미")
class ChunkedCallsTest {

    private static final SupplierId 공급사 = new SupplierId("A");
    private static final Duration 결과대기 = Duration.ofSeconds(2);

    @Test
    @DisplayName("코드 120개를 50개씩 나누면 입력 순서 앞에서부터 50, 50, 20 묶음이다")
    void 코드120개는_50_50_20으로_나뉜다() {
        List<String> 코드 = 코드들(120);
        List<List<String>> 받은묶음 = Collections.synchronizedList(new ArrayList<>());

        모은다(코드, 50, 8, null, 묶음 -> {
            받은묶음.add(묶음);
            return 성공(묶음);
        });

        List<List<String>> 순서대로 = new ArrayList<>(받은묶음);
        순서대로.sort((왼쪽, 오른쪽) -> 코드.indexOf(왼쪽.get(0)) - 코드.indexOf(오른쪽.get(0)));
        assertThat(순서대로, equalTo(List.of(코드.subList(0, 50), 코드.subList(50, 100), 코드.subList(100, 120))));
    }

    @Test
    @DisplayName("코드 50개는 한 묶음이고 51개는 50과 1 두 묶음이다")
    void 묶음_경계() {
        List<Integer> 오십개묶음크기 = 묶음크기들(코드들(50));
        List<Integer> 오십일개묶음크기 = 묶음크기들(코드들(51));

        assertThat(오십개묶음크기, equalTo(List.of(50)));
        assertThat(오십일개묶음크기, equalTo(List.of(50, 1)));
    }

    @Test
    @DisplayName("30 묶음을 동시성 8로 호출하면 동시에 실행되는 묶음은 최대 8개다")
    void 동시성_상한은_8이다() {
        AtomicInteger 실행중 = new AtomicInteger();
        AtomicInteger 최대실행중 = new AtomicInteger();

        SupplierSearchResult 결과 = 모은다(코드들(30), 1, 8, null, 묶음 -> Mono.defer(() -> {
            최대실행중.accumulateAndGet(실행중.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofMillis(50)).map(ignored -> {
                실행중.decrementAndGet();
                return 오퍼결과(묶음);
            });
        }));

        assertThat(최대실행중.get(), equalTo(8));
        assertThat(결과.offers().size(), equalTo(30));
    }

    @Test
    @DisplayName("한 묶음이 오류면 그 묶음만 실패하고 다른 묶음 오퍼는 모두 남는다")
    void 묶음_오류는_그묶음만_실패다() {
        List<String> 코드 = 코드들(3);

        SupplierSearchResult 결과 = 모은다(코드, 1, 8, null, 묶음 -> 묶음.contains("C002")
                ? Mono.error(new IllegalStateException())
                : 성공(묶음));

        assertThat(오퍼코드(결과), equalTo(List.of("C001", "C003")));
        assertThat(결과.failures(), equalTo(List.of(new ChunkFailure(공급사, "IllegalStateException"))));
    }

    @Test
    @DisplayName("한 묶음이 빈 Mono면 그 묶음만 EMPTY_RESULT 실패다")
    void 빈_묶음은_EMPTY_RESULT다() {
        SupplierSearchResult 결과 = 모은다(코드들(3), 1, 8, null, 묶음 -> 묶음.contains("C002")
                ? Mono.<SupplierSearchResult>empty()
                : 성공(묶음));

        assertThat(오퍼코드(결과), equalTo(List.of("C001", "C003")));
        assertThat(결과.failures(), equalTo(List.of(new ChunkFailure(공급사, "EMPTY_RESULT"))));
    }

    @Test
    @DisplayName("예산이 지나면 끝난 묶음 오퍼는 남고 못 끝난 묶음은 BUDGET_EXCEEDED 실패다")
    void 예산만료는_못끝난묶음만_실패다() {
        long 시작 = System.nanoTime();

        SupplierSearchResult 결과 = 모은다(코드들(3), 1, 8, Duration.ofMillis(200), 묶음 -> 묶음.contains("C002")
                ? Mono.<SupplierSearchResult>never()
                : 성공(묶음));

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - 시작), lessThan(2000L));
        assertThat(오퍼코드(결과), equalTo(List.of("C001", "C003")));
        assertThat(결과.failures(), equalTo(List.of(new ChunkFailure(공급사, "BUDGET_EXCEEDED"))));
    }

    @Test
    @DisplayName("결과 오퍼 순서는 완료 순서가 아니라 묶음 순서다")
    void 결과는_묶음순서다() {
        SupplierSearchResult 결과 = 모은다(코드들(2), 1, 8, null, 묶음 -> 묶음.contains("C001")
                ? 성공(묶음).delayElement(Duration.ofMillis(100))
                : 성공(묶음));

        assertThat(오퍼코드(결과), equalTo(List.of("C001", "C002")));
    }

    @Test
    @DisplayName("예산이 null이면 늦은 묶음도 기다려 결과에 넣는다")
    void 예산없으면_모두_기다린다() {
        SupplierSearchResult 결과 = 모은다(코드들(2), 1, 8, null, 묶음 -> 묶음.contains("C002")
                ? 성공(묶음).delayElement(Duration.ofMillis(150))
                : 성공(묶음));

        assertThat(오퍼코드(결과), equalTo(List.of("C001", "C002")));
        assertThat(결과.failures(), equalTo(List.of()));
    }

    @Test
    @DisplayName("묶음 크기나 동시성이 0이면 IllegalArgumentException이다")
    void 잘못된_인자는_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> 모은다(코드들(3), 0, 8, null, ChunkedCallsTest::성공));
        assertThrows(IllegalArgumentException.class, () -> 모은다(코드들(3), 1, 0, null, ChunkedCallsTest::성공));
    }

    private static SupplierSearchResult 모은다(
            List<String> 코드,
            int 묶음크기,
            int 동시성,
            Duration 예산,
            Function<List<String>, Mono<SupplierSearchResult>> 묶음호출) {
        return ChunkedCalls.collect(공급사, 코드, 묶음크기, 동시성, 예산, 묶음호출).block(결과대기);
    }

    private static List<Integer> 묶음크기들(List<String> 코드) {
        List<List<String>> 받은묶음 = Collections.synchronizedList(new ArrayList<>());
        모은다(코드, 50, 8, null, 묶음 -> {
            받은묶음.add(묶음);
            return 성공(묶음);
        });
        return 받은묶음.stream().map(List::size).sorted((왼쪽, 오른쪽) -> 오른쪽 - 왼쪽).toList();
    }

    private static List<String> 코드들(int 개수) {
        return IntStream.rangeClosed(1, 개수).mapToObj(n -> "C%03d".formatted(n)).toList();
    }

    private static Mono<SupplierSearchResult> 성공(List<String> 묶음) {
        return Mono.just(오퍼결과(묶음));
    }

    private static SupplierSearchResult 오퍼결과(List<String> 묶음) {
        List<RoomOffer> offers = 묶음.stream()
                .map(code -> new RoomOffer(code, "DLX-TWN", "숙소 " + code, "디럭스", 2, false, 100000, "KRW", List.of(1)))
                .toList();
        return new SupplierSearchResult(공급사, offers, List.of());
    }

    private static List<String> 오퍼코드(SupplierSearchResult 결과) {
        return 결과.offers().stream().map(RoomOffer::supplierPropertyCode).toList();
    }
}

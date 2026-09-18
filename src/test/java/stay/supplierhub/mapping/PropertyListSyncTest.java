package stay.supplierhub.mapping;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import stay.supplierhub.StaySupplierHubApplication;
import stay.supplierhub.mapping.MappingStore.MappingSnapshot;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("숙소 목록 동기화")
class PropertyListSyncTest {

    private static final SupplierId 공급사A = new SupplierId("A");
    private static final MockWebServer 카탈로그서버 = new MockWebServer();
    private static final AtomicInteger 목록호출수 = new AtomicInteger();
    private static final AtomicReference<String> 목록본문 = new AtomicReference<>(숙소목록json(10));
    private static final AtomicReference<Boolean> 목록실패 = new AtomicReference<>(false);
    private static final AtomicReference<CountDownLatch> 진입 = new AtomicReference<>(new CountDownLatch(1));
    private static final AtomicReference<CountDownLatch> 해제 = new AtomicReference<>(new CountDownLatch(0));
    private static final 조절시계 시계 = new 조절시계(
            ZonedDateTime.of(2026, 9, 19, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));

    static {
        try {
            카탈로그서버.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        카탈로그서버.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (!"/a/v1/hotels".equals(path)) {
                    return new MockResponse().setResponseCode(404);
                }
                목록호출수.incrementAndGet();
                진입.get().countDown();
                try {
                    if (!해제.get().await(10, TimeUnit.SECONDS)) {
                        return new MockResponse().setResponseCode(504);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new MockResponse().setResponseCode(500);
                }
                if (Boolean.TRUE.equals(목록실패.get())) {
                    return new MockResponse().setResponseCode(500).setBody("{\"error\":\"catalog down\"}");
                }
                return new MockResponse()
                        .setHeader("Content-Type", "application/json;charset=UTF-8")
                        .setBody(목록본문.get());
            }
        });
    }

    @Autowired
    MappingSync.PropertyListSynchronizer synchronizer;

    @Autowired
    MappingStore.MappingSnapshotHolder snapshotHolder;

    @Autowired
    UnmappedCodeBackoffRepository backoffRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 카탈로그서버.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:property-list-sync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }

    @TestConfiguration
    static class 고정시계설정 {
        @Bean
        @Primary
        Clock fixedClock() {
            return 시계;
        }
    }

    @AfterAll
    static void 서버를_종료한다() throws IOException {
        카탈로그서버.shutdown();
    }

    @BeforeEach
    void 초기화() {
        jdbcTemplate.update("delete from mapping_unmapped_code_backoff");
        jdbcTemplate.update("delete from mapping_supplier_readiness");
        jdbcTemplate.update("delete from room_type");
        jdbcTemplate.update("delete from property");
        snapshotHolder.replace(MappingSnapshot.unready());
        목록호출수.set(0);
        목록본문.set(숙소목록json(10));
        목록실패.set(false);
        진입.set(new CountDownLatch(1));
        해제.set(new CountDownLatch(0));
        시계.시각을(ZonedDateTime.of(2026, 9, 19, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant());
    }

    @Test
    @DisplayName("기동 시 카탈로그를 한 번 호출하고 커밋 뒤 스냅샷이 바뀐다")
    void 기동_시_목록을_반영한다() {
        목록본문.set(숙소목록json(2));
        synchronizer.runSyncCatching();
        assertThat(목록호출수.get(), equalTo(1));
        assertThat(snapshotHolder.current().ready(), equalTo(true));
        assertThat(snapshotHolder.current().activePropertyCodes(공급사A).size(), equalTo(2));
        assertThat(활성숙소수(), equalTo(2L));
    }

    @Test
    @DisplayName("기동 시 카탈로그가 실패해도 프로세스는 살아 있고 이전 스냅샷을 유지한다")
    void 기동_실패해도_이전_매핑을_유지한다() {
        목록본문.set(숙소목록json(2));
        synchronizer.runSyncCatching();
        assertThat(snapshotHolder.current().ready(), equalTo(true));
        long 이전활성 = 활성숙소수();
        목록실패.set(true);
        진입.set(new CountDownLatch(1));
        synchronizer.runSyncCatching();
        assertThat(snapshotHolder.current().ready(), equalTo(true));
        assertThat(snapshotHolder.current().activePropertyCodes(공급사A).size(), equalTo(2));
        assertThat(활성숙소수(), equalTo(이전활성));
    }

    @Test
    @DisplayName("주기가 돌 때 이미 실행 중이면 그 틱은 건너뛴다")
    void 실행중이면_주기는_건너뛴다() throws Exception {
        해제.set(new CountDownLatch(1));
        synchronizer.requestManual();
        assertThat(진입.get().await(2, TimeUnit.SECONDS), equalTo(true));
        int 호출 = 목록호출수.get();
        synchronizer.onSchedule();
        해제.get().countDown();
        활성숙소가_될때까지_기다린다(10);
        assertThat(목록호출수.get(), equalTo(호출));
    }

    @Test
    @DisplayName("수동 동기화 신호가 여러 번이어도 실행 중이면 끝난 뒤 한 번 더 돌고 예약은 하나다")
    void 수동_신호는_하나로_합친다() throws Exception {
        해제.set(new CountDownLatch(1));
        mockMvc.perform(post("/internal/mapping/sync")).andExpect(status().is2xxSuccessful());
        assertThat(진입.get().await(2, TimeUnit.SECONDS), equalTo(true));
        mockMvc.perform(post("/internal/mapping/sync")).andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/internal/mapping/sync")).andExpect(status().is2xxSuccessful());
        해제.get().countDown();
        호출수가_될때까지_기다린다(2);
        assertThat(목록호출수.get(), equalTo(2));
        활성숙소가_될때까지_기다린다(10);
    }

    @Test
    @DisplayName("POST /internal/mapping/sync 는 인증 없이 즉시 응답하고 검색을 막지 않는다")
    void 수동동기화는_즉시_응답한다() throws Exception {
        해제.set(new CountDownLatch(1));
        long 시작 = System.nanoTime();
        mockMvc.perform(post("/internal/mapping/sync")).andExpect(status().is2xxSuccessful());
        long 경과ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - 시작);
        assertThat(경과ms < 500L, equalTo(true));
        assertThat(진입.get().await(2, TimeUnit.SECONDS), equalTo(true));
        snapshotHolder.replace(MappingSnapshot.readyEmpty());
        mockMvc.perform(get("/api/v1/stays/search")
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-22")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties.length()").value(0));
        해제.get().countDown();
        활성숙소가_될때까지_기다린다(10);
    }

    @Test
    @DisplayName("이전 활성 숙소 10개인데 새 목록이 6개면 비율 0.30에서 반영하지 않는다")
    void 급감하면_반영하지_않는다() {
        synchronizer.runSyncCatching();
        assertThat(활성숙소수(), equalTo(10L));
        assertThat(snapshotHolder.current().activePropertyCodes(공급사A).size(), equalTo(10));
        목록본문.set(숙소목록json(6));
        진입.set(new CountDownLatch(1));
        synchronizer.runSyncCatching();
        assertThat(활성숙소수(), equalTo(10L));
        assertThat(snapshotHolder.current().activePropertyCodes(공급사A).size(), equalTo(10));
    }

    @Test
    @DisplayName("새 목록이 비어 있으면 반영하지 않는다")
    void 빈_목록은_반영하지_않는다() {
        synchronizer.runSyncCatching();
        assertThat(활성숙소수(), equalTo(10L));
        목록본문.set("{\"items\":[]}");
        진입.set(new CountDownLatch(1));
        synchronizer.runSyncCatching();
        assertThat(활성숙소수(), equalTo(10L));
        assertThat(snapshotHolder.current().activePropertyCodes(공급사A).size(), equalTo(10));
        assertThat(snapshotHolder.current().ready(), equalTo(true));
    }

    @Test
    @DisplayName("매핑 없는 객실 타입 이벤트는 3분 창 안에서는 다시 동기화하지 않는다")
    void 백오프_3분_창() throws Exception {
        synchronizer.requestForUnmapped(공급사A, "A-99999", "UNKNOWN");
        호출수가_될때까지_기다린다(1);
        활성숙소가_될때까지_기다린다(10);
        UnmappedCodeBackoffEntity 행 = backoffRepository
                .findBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode("A", "A-99999", "UNKNOWN")
                .orElse(null);
        assertThat(행, notNullValue());
        assertThat(행.nextEligibleAt, equalTo(시계.instant().plus(Duration.ofMinutes(3))));
        int 호출 = 목록호출수.get();
        synchronizer.requestForUnmapped(공급사A, "A-99999", "UNKNOWN");
        Thread.sleep(150);
        assertThat(목록호출수.get(), equalTo(호출));
    }

    @Test
    @DisplayName("매핑이 생기면 백오프 행을 지운다")
    void 매핑이_생기면_백오프를_지운다() throws Exception {
        synchronizer.requestForUnmapped(공급사A, "A-10001", "STD");
        호출수가_될때까지_기다린다(1);
        활성숙소가_될때까지_기다린다(10);
        assertThat(
                backoffRepository
                        .findBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode("A", "A-10001", "STD")
                        .isEmpty(),
                equalTo(true));
    }

    @Test
    @DisplayName("조율용 락 테이블은 없고 준비·백오프 테이블만 있다")
    void 락_테이블이_없다() {
        Integer 준비 = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where upper(table_name) = 'MAPPING_SUPPLIER_READINESS'",
                Integer.class);
        Integer 백오프 = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where upper(table_name) = 'MAPPING_UNMAPPED_CODE_BACKOFF'",
                Integer.class);
        Integer 락 = jdbcTemplate.queryForObject(
                """
                select count(*) from information_schema.tables
                 where upper(table_name) in (
                    'MAPPING_SYNC_LOCK', 'MAPPING_SYNC_ATTEMPT', 'MAPPING_SYNC_COORDINATION')
                """,
                Integer.class);
        assertThat(준비, equalTo(1));
        assertThat(백오프, equalTo(1));
        assertThat(락, equalTo(0));
    }

    @Test
    @DisplayName("주기 설정은 6시간이고 스케줄링이 켜져 있다")
    void 주기_설정과_스케줄링() {
        assertThat(StaySupplierHubApplication.class.isAnnotationPresent(EnableScheduling.class), equalTo(true));
        assertThat(new MappingProperties(true, Duration.ofHours(6), 0.30).syncInterval(), equalTo(Duration.ofHours(6)));
    }

    private long 활성숙소수() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from property where supplier = 'A' and active = true", Long.class);
        return count == null ? 0L : count;
    }

    private void 활성숙소가_될때까지_기다린다(int n) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (snapshotHolder.current().ready()
                    && snapshotHolder.current().activePropertyCodes(공급사A).size() == n) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "활성 숙소가 " + n + "이 아니다: " + snapshotHolder.current().activePropertyCodes(공급사A));
    }

    private void 호출수가_될때까지_기다린다(int n) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (목록호출수.get() >= n) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("카탈로그 호출 수가 " + n + "이 아니다: " + 목록호출수.get());
    }

    private static String 숙소목록json(int 개수) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 1; i <= 개수; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"hotelCode\":\"A-")
                    .append(10000 + i)
                    .append("\",\"hotelName\":\"Hotel ")
                    .append(i)
                    .append("\",\"roomTypes\":[{\"roomTypeCode\":\"STD\",\"roomTypeName\":\"Standard\",\"maxOccupancy\":2}]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static final class 조절시계 extends Clock {
        private final ZoneId zone;
        private final AtomicReference<Instant> instant;

        조절시계(Instant instant, ZoneId zone) {
            this.zone = zone;
            this.instant = new AtomicReference<>(instant);
        }

        void 시각을(Instant 시각) {
            instant.set(시각);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new 조절시계(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}

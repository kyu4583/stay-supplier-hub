package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("검색 전체 예산")
class StaySearchBudgetIntegrationTest {

    private static final String 검색경로 = "/api/v1/stays/search";
    private static final String A재고경로 = "/a/v1/availability";
    private static final String B재고경로 = "/b/api/search";
    private static final List<String> A숙소코드 =
            IntStream.rangeClosed(20001, 20120).mapToObj(n -> "A-" + n).toList();
    private static final List<String> B숙소코드 = List.of("B90001");
    private static final long 예산상한밀리초 = 2500;

    private static final MockWebServer 공급사A = new MockWebServer();
    private static final MockWebServer 공급사B = new MockWebServer();

    static {
        try {
            공급사A.start();
            공급사B.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MappingStore.MappingUpsertService mappingUpsertService;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.supplier.a.response-timeout", () -> "4s");
        registry.add("stay.supplier.a.connect-timeout", () -> "1s");
        registry.add("stay.supplier.b.base-url", () -> 공급사B.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.b.api-key", () -> "demo-b-key");
        registry.add("stay.supplier.b.response-timeout", () -> "4s");
        registry.add("stay.supplier.b.connect-timeout", () -> "1s");
        registry.add("stay.supplier.b.max-connections", () -> "1");
        registry.add("stay.supplier.b.pending-acquire-timeout", () -> "200ms");
        registry.add("stay.search.budget", () -> "1s");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-budget;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }

    @TestConfiguration
    static class 고정시계설정 {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            Instant instant = ZonedDateTime.of(2026, 9, 19, 0, 0, 0, 0, seoul).toInstant();
            return Clock.fixed(instant, seoul);
        }
    }

    @AfterAll
    static void 공급사를_종료한다() throws IOException {
        공급사A.shutdown();
        공급사B.shutdown();
    }

    @BeforeEach
    void 카탈로그와_재고응답을_준비한다() throws InterruptedException {
        mappingUpsertService.apply(new SupplierCatalog(
                new SupplierId("A"),
                A숙소코드.stream()
                        .map(code -> new CatalogProperty(
                                code, "Demo Hotel " + code, List.of(new CatalogRoomType("DLX-TWN", "Deluxe Twin", 2))))
                        .toList()));
        B카탈로그를_반영한다(B숙소코드);
        공급사A재고를(codes -> json응답(A재고본문(codes)));
        공급사B재고를(codes -> json응답(B재고본문(codes)));
        기록을_비운다(공급사A);
        기록을_비운다(공급사B);
    }

    @Test
    @DisplayName("A가 연결만 받고 응답하지 않아도 호출별 응답 타임아웃보다 먼저 예산 안에 200이 오고 failedSuppliers는 A다")
    void A_무응답은_예산안에_200이다() throws Exception {
        공급사A재고를(codes -> 무응답());

        long 시작 = System.nanoTime();
        MvcResult result = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("A"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andReturn();
        예산안에_끝났는지_검증한다(시작);

        String json = result.getResponse().getContentAsString();
        실패사유가_없는지_검증한다(json);
        assertThat(숙소이름(json, "Demo Hotel "), equalTo(List.of()));
        List<Number> B요금 = JsonPath.read(
                json, "$.properties[?(@.propertyName == 'Demo Stay B90001')].roomTypes[*].offers[*].totalPrice");
        assertThat(B요금.stream().map(Number::longValue).toList(), equalTo(List.of(452000L)));
    }

    @Test
    @DisplayName("호출한 A와 B가 모두 응답하지 않으면 예산 안에 503 problem+json이고 Retry-After가 없다")
    void A와_B_모두_무응답은_예산안에_503이다() throws Exception {
        공급사A재고를(codes -> 무응답());
        공급사B재고를(codes -> 무응답());

        long 시작 = System.nanoTime();
        MvcResult result = 검색한다()
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(2))
                .andReturn();
        예산안에_끝났는지_검증한다(시작);

        String json = result.getResponse().getContentAsString();
        List<String> 실패공급사 = JsonPath.read(json, "$.failedSuppliers[*].supplier");
        assertThat(실패공급사, containsInAnyOrder("A", "B"));
        실패사유가_없는지_검증한다(json);
    }

    private static void 예산안에_끝났는지_검증한다(long 시작) {
        long 경과밀리초 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - 시작);
        assertThat(경과밀리초, lessThan(예산상한밀리초));
    }

    private static void 실패사유가_없는지_검증한다(String json) {
        for (String 사유 : List.of("BUDGET_EXCEEDED", "TimeoutException", "Exception", "reason")) {
            assertThat(json, not(containsString(사유)));
        }
    }

    private void B카탈로그를_반영한다(List<String> codes) {
        mappingUpsertService.apply(new SupplierCatalog(
                new SupplierId("B"),
                codes.stream()
                        .map(code -> new CatalogProperty(
                                code, "Demo Stay " + code, List.of(new CatalogRoomType("R-401", "Deluxe Twin Room", 2))))
                        .toList()));
    }

    private static List<String> 숙소이름(String json, String 접두사) {
        List<String> names = JsonPath.read(json, "$.properties[*].propertyName");
        return names.stream().filter(name -> name.startsWith(접두사)).toList();
    }

    private static String A재고본문(List<String> codes) {
        List<String> items = codes.stream()
                .map(code -> """
                        {"hotelCode":"%s","hotelName":"Demo Hotel %s","roomTypeCode":"DLX-TWN","roomTypeName":"Deluxe Twin",\
                        "maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[\
                        {"date":"2026-09-20","remainingRooms":3,"nightlyRate":120000,"taxAmount":12000},\
                        {"date":"2026-09-21","remainingRooms":1,"nightlyRate":150000,"taxAmount":15000},\
                        {"date":"2026-09-22","remainingRooms":5,"nightlyRate":120000,"taxAmount":12000}]}"""
                        .formatted(code, code))
                .toList();
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String B재고본문(List<String> codes) {
        List<String> items = codes.stream()
                .map(code -> """
                        {"propertyId":"%s","propertyName":"Demo Stay %s","roomId":"R-401","roomName":"Deluxe Twin Room",\
                        "maxOccupancy":2,"breakfastIncluded":true,"currency":"KRW","totalPrice":452000,"taxIncluded":true,\
                        "inventory":[{"date":"2026-09-20","remainingRooms":3},{"date":"2026-09-21","remainingRooms":1},\
                        {"date":"2026-09-22","remainingRooms":5}]}"""
                        .formatted(code, code))
                .toList();
        return "{\"resultCode\":\"0000\",\"resultMessage\":\"SUCCESS\",\"data\":{\"items\":["
                + String.join(",", items) + "]}}";
    }

    private static void 공급사A재고를(Function<List<String>, MockResponse> 응답) {
        공급사A.setDispatcher(재고디스패처(A재고경로, "hotelCodes", 응답));
    }

    private static void 공급사B재고를(Function<List<String>, MockResponse> 응답) {
        공급사B.setDispatcher(재고디스패처(B재고경로, "propertyIds", 응답));
    }

    private static Dispatcher 재고디스패처(
            String 경로, String 코드파라미터, Function<List<String>, MockResponse> 응답) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (경로.equals(request.getRequestUrl().encodedPath())) {
                    return 응답.apply(요청코드(request, 코드파라미터));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static List<String> 요청코드(RecordedRequest request, String 코드파라미터) {
        String value = request.getRequestUrl().queryParameter(코드파라미터);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.asList(value.split(","));
    }

    private static void 기록을_비운다(MockWebServer 서버) throws InterruptedException {
        while (서버.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 이전 요청 기록을 비운다
        }
    }

    private static MockResponse 무응답() {
        return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
    }

    private ResultActions 검색한다() throws Exception {
        return mockMvc.perform(get(검색경로)
                .param("checkIn", "2026-09-20")
                .param("checkOut", "2026-09-23")
                .param("adults", "2")
                .param("children", "0"));
    }

    private static MockResponse json응답(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body);
    }
}

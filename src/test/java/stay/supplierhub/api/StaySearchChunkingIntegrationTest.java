package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("숙소 코드 50개 초과 묶음 분할 검색")
class StaySearchChunkingIntegrationTest {

    private static final String 검색경로 = "/api/v1/stays/search";
    private static final String A재고경로 = "/a/v1/availability";
    private static final String A숙소이름접두사 = "Demo Hotel A-";
    private static final List<String> A숙소코드 =
            IntStream.rangeClosed(20001, 20120).mapToObj(n -> "A-" + n).toList();
    private static final String B재고경로 = "/b/api/search";
    private static final String B숙소이름접두사 = "Demo Stay B";
    private static final List<String> B숙소코드 =
            IntStream.rangeClosed(90001, 90120).mapToObj(n -> "B" + n).toList();
    private static final LocalDate 요청체크인 = LocalDate.of(2026, 9, 20);
    private static final String A표식코드 = "A-20060";
    private static final String B표식코드 = "B90060";
    private static final String B장애본문 =
            "{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}";

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

    @Autowired
    MappingStore.MappingSnapshotHolder snapshotHolder;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.supplier.a.response-timeout", () -> "1s");
        registry.add("stay.supplier.a.connect-timeout", () -> "1s");
        registry.add("stay.supplier.b.base-url", () -> 공급사B.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.b.api-key", () -> "demo-b-key");
        registry.add("stay.supplier.b.response-timeout", () -> "1s");
        registry.add("stay.supplier.b.connect-timeout", () -> "1s");
        registry.add("stay.search.budget", () -> "4s");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-chunking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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
                                code, A숙소이름접두사 + code.substring(2),
                                List.of(new CatalogRoomType("DLX-TWN", "Deluxe Twin", 2))))
                        .toList()));
        mappingUpsertService.apply(new SupplierCatalog(
                new SupplierId("B"),
                B숙소코드.stream()
                        .map(code -> new CatalogProperty(
                                code, "Demo Stay " + code,
                                List.of(new CatalogRoomType("R-401", "Deluxe Twin Room", 2))))
                        .toList()));
        공급사A재고를(codes -> json응답(A재고본문(codes)));
        공급사B재고를(codes -> json응답(B재고본문(codes)));
        기록을_비운다(공급사A);
        기록을_비운다(공급사B);
    }

    @Test
    @DisplayName("A 숙소 코드 120개 검색은 50개 이하 3번 호출로 나뉘고 한 200 응답에 120개 숙소로 합쳐진다")
    void A_120개는_3묶음으로_나가_200으로_합쳐진다() throws Exception {
        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소이름(json, A숙소이름접두사).size(), equalTo(120));
        List<List<String>> 묶음들 = 기록된_묶음(공급사A, A재고경로, "hotelCodes");
        묶음_분할을_검증한다(묶음들, A숙소코드);
    }

    @Test
    @DisplayName("B 숙소 코드 120개 검색은 propertyIds 50개 이하 3번 호출로 나뉜다")
    void B_120개는_3묶음으로_나간다() throws Exception {
        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소이름(json, B숙소이름접두사).size(), equalTo(120));
        묶음_분할을_검증한다(기록된_묶음(공급사B, B재고경로, "propertyIds"), B숙소코드);
    }

    @Test
    @DisplayName("A 한 묶음만 HTTP 500이면 200이고 그 묶음 숙소만 빠지며 failedSuppliers는 A다")
    void A_한묶음_HTTP500은_그묶음만_빠진다() throws Exception {
        공급사A재고를(codes -> codes.contains(A표식코드)
                ? new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}")
                : json응답(A재고본문(codes)));

        String json = 부분실패_200을_검증한다("A");

        A_실패묶음만_빠졌는지_검증한다(json);
        assertThat(숙소이름(json, B숙소이름접두사).size(), equalTo(120));
    }

    @Test
    @DisplayName("B 한 묶음만 resultCode E503이면 200이고 그 묶음 밖 B 숙소는 모두 있다")
    void B_한묶음_E503은_그묶음만_빠진다() throws Exception {
        공급사B재고를(codes -> codes.contains(B표식코드) ? json응답(B장애본문) : json응답(B재고본문(codes)));

        String json = 부분실패_200을_검증한다("B");

        B_실패묶음만_빠졌는지_검증한다(json);
        assertThat(json, not(containsString("resultMessage")));
        assertThat(json, not(containsString("TEMPORARILY_UNAVAILABLE")));
        assertThat(숙소이름(json, A숙소이름접두사).size(), equalTo(120));
    }

    @Test
    @DisplayName("A 한 묶음만 응답 타임아웃을 넘기면 200이고 다른 A 묶음 숙소는 남는다")
    void A_한묶음_응답타임아웃은_그묶음만_빠진다() throws Exception {
        공급사A재고를(codes -> codes.contains(A표식코드)
                ? json응답(A재고본문(codes)).setHeadersDelay(3, TimeUnit.SECONDS)
                : json응답(A재고본문(codes)));

        String json = 부분실패_200을_검증한다("A");

        A_실패묶음만_빠졌는지_검증한다(json);
    }

    @Test
    @DisplayName("A 한 묶음의 모든 항목 날짜가 요청 박과 다르면 그 묶음만 실패해 200이고 다른 A 묶음 숙소는 남는다")
    void A_한묶음_전항목제외는_그묶음만_실패다() throws Exception {
        공급사A재고를(codes -> codes.contains(A표식코드)
                ? json응답(A재고본문(codes, LocalDate.of(2026, 10, 1)))
                : json응답(A재고본문(codes)));

        String json = 부분실패_200을_검증한다("A");

        A_실패묶음만_빠졌는지_검증한다(json);
    }

    @Test
    @DisplayName("B 한 묶음의 items가 빈 배열이면 정상 빈 결과라 failedSuppliers에 넣지 않는다")
    void B_한묶음_빈items는_실패가_아니다() throws Exception {
        공급사B재고를(codes -> codes.contains(B표식코드)
                ? json응답("{\"resultCode\":\"0000\",\"resultMessage\":\"SUCCESS\",\"data\":{\"items\":[]}}")
                : json응답(B재고본문(codes)));

        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        B_실패묶음만_빠졌는지_검증한다(json);
    }

    @Test
    @DisplayName("한 묶음 응답에 그 묶음 요청 밖 코드 항목이 섞이면 그 항목은 빠지고 해당 숙소는 자기 묶음 오퍼 하나만 있다")
    void 묶음밖_코드_항목은_제외된다() throws Exception {
        AtomicReference<String> 섞인코드 = new AtomicReference<>();
        공급사A재고를(codes -> {
            if (!codes.contains(A표식코드)) {
                return json응답(A재고본문(codes));
            }
            String 다른묶음코드 = A숙소코드.stream().filter(code -> !codes.contains(code)).findFirst().orElseThrow();
            섞인코드.set(다른묶음코드);
            List<String> items = new ArrayList<>(codes.stream().map(code -> A재고항목(code, 요청체크인, 120000, 12000)).toList());
            items.add(A재고항목(다른묶음코드, 요청체크인, 700000, 2000));
            return json응답("{\"items\":[" + String.join(",", items) + "]}");
        });

        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Number> 전체요금 = JsonPath.read(json, "$.properties[*].roomTypes[*].offers[*].totalPrice");
        assertThat(전체요금.stream().map(Number::longValue).toList(), not(hasItem(999000L)));
        List<Number> 섞인숙소요금 = JsonPath.read(
                json,
                "$.properties[?(@.propertyName == 'Demo Hotel " + 섞인코드.get() + "')].roomTypes[*].offers[*].totalPrice");
        assertThat(섞인숙소요금.stream().map(Number::longValue).toList(), equalTo(List.of(429000L)));
    }

    @Test
    @DisplayName("A만 참가할 때 일부 묶음만 실패하고 오퍼가 남으면 503이 아니라 200이고 failedSuppliers는 A다")
    void A만_참가_일부묶음실패는_200이다() throws Exception {
        A만_참가시킨다();
        공급사A재고를(codes -> codes.contains(A표식코드)
                ? new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}")
                : json응답(A재고본문(codes)));

        String json = 부분실패_200을_검증한다("A");

        A_실패묶음만_빠졌는지_검증한다(json);
        assertThat(숙소이름(json, B숙소이름접두사).size(), equalTo(0));
        assertThat(기록된_묶음(공급사B, B재고경로, "propertyIds").size(), equalTo(0));
    }

    @Test
    @DisplayName("A만 참가할 때 모든 묶음이 실패하면 503 problem+json이고 Retry-After가 없다")
    void A만_참가_전묶음실패는_503이다() throws Exception {
        A만_참가시킨다();
        공급사A재고를(codes -> new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}"));

        검색한다()
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("A"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist());
    }

    private void A만_참가시킨다() {
        snapshotHolder.replace(MappingStore.MappingSnapshot.explicit(
                snapshotHolder.current().properties(), Set.of(new SupplierId("A")), Set.of()));
    }

    private String 부분실패_200을_검증한다(String supplier) throws Exception {
        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value(supplier))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        실패사유가_없는지_검증한다(json);
        return json;
    }

    private void A_실패묶음만_빠졌는지_검증한다(String json) throws InterruptedException {
        List<String> 실패묶음 = 표식묶음(기록된_묶음(공급사A, A재고경로, "hotelCodes"), A표식코드);
        Set<String> 기대코드 = new LinkedHashSet<>(A숙소코드);
        실패묶음.forEach(기대코드::remove);
        assertThat(응답코드(json, A숙소이름접두사, "Demo Hotel "), equalTo(기대코드));
    }

    private void B_실패묶음만_빠졌는지_검증한다(String json) throws InterruptedException {
        List<String> 실패묶음 = 표식묶음(기록된_묶음(공급사B, B재고경로, "propertyIds"), B표식코드);
        Set<String> 기대코드 = new LinkedHashSet<>(B숙소코드);
        실패묶음.forEach(기대코드::remove);
        assertThat(응답코드(json, B숙소이름접두사, "Demo Stay "), equalTo(기대코드));
    }

    private static void 실패사유가_없는지_검증한다(String json) {
        for (String 사유 : List.of("HTTP_", "INVALID_RESPONSE", "EMPTY_RESULT", "BUDGET_EXCEEDED", "Exception", "reason")) {
            assertThat(json, not(containsString(사유)));
        }
    }

    private static List<String> 표식묶음(List<List<String>> 묶음들, String 표식코드) {
        List<String> 표식 = 묶음들.stream().filter(묶음 -> 묶음.contains(표식코드)).findFirst().orElseThrow();
        assertThat(표식, hasItem(표식코드));
        return 표식;
    }

    private static Set<String> 응답코드(String json, String 접두사, String 이름앞부분) {
        return 숙소이름(json, 접두사).stream()
                .map(name -> name.substring(이름앞부분.length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void 묶음_분할을_검증한다(List<List<String>> 묶음들, List<String> 전체코드) {
        assertThat(묶음들.size(), equalTo(3));
        Set<String> 합집합 = new LinkedHashSet<>();
        int 코드수합 = 0;
        for (List<String> 묶음 : 묶음들) {
            assertThat(묶음.size(), lessThanOrEqualTo(50));
            코드수합 += 묶음.size();
            합집합.addAll(묶음);
        }
        assertThat(코드수합, equalTo(전체코드.size()));
        assertThat(합집합, equalTo(new LinkedHashSet<>(전체코드)));
    }

    private static List<List<String>> 기록된_묶음(MockWebServer 서버, String 경로, String 코드파라미터)
            throws InterruptedException {
        List<List<String>> 묶음들 = new ArrayList<>();
        RecordedRequest request;
        while ((request = 서버.takeRequest(200, TimeUnit.MILLISECONDS)) != null) {
            if (경로.equals(request.getRequestUrl().encodedPath())) {
                묶음들.add(요청코드(request, 코드파라미터));
            }
        }
        return 묶음들;
    }

    private static List<String> 요청코드(RecordedRequest request, String 코드파라미터) {
        String value = request.getRequestUrl().queryParameter(코드파라미터);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.asList(value.split(","));
    }

    private static List<String> 숙소이름(String json, String 접두사) {
        List<String> names = JsonPath.read(json, "$.properties[*].propertyName");
        return names.stream().filter(name -> name.startsWith(접두사)).toList();
    }

    private static String A재고본문(List<String> codes) {
        return A재고본문(codes, 요청체크인);
    }

    private static String A재고본문(List<String> codes, LocalDate 첫날) {
        List<String> items = codes.stream().map(code -> A재고항목(code, 첫날, 120000, 12000)).toList();
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String A재고항목(String code, LocalDate 첫날, long 첫박요금, long 첫박세금) {
        return """
                {"hotelCode":"%s","hotelName":"Demo Hotel %s","roomTypeCode":"DLX-TWN","roomTypeName":"Deluxe Twin",\
                "maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[\
                {"date":"%s","remainingRooms":3,"nightlyRate":%d,"taxAmount":%d},\
                {"date":"%s","remainingRooms":1,"nightlyRate":150000,"taxAmount":15000},\
                {"date":"%s","remainingRooms":5,"nightlyRate":120000,"taxAmount":12000}]}"""
                .formatted(code, code, 첫날, 첫박요금, 첫박세금, 첫날.plusDays(1), 첫날.plusDays(2));
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

    private static void 기록을_비운다(MockWebServer 서버) throws InterruptedException {
        while (서버.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 이전 요청 기록을 비운다
        }
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

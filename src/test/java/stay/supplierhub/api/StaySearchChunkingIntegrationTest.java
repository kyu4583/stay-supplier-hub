package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
        registry.add("stay.supplier.a.response-timeout", () -> "300ms");
        registry.add("stay.supplier.a.connect-timeout", () -> "300ms");
        registry.add("stay.supplier.b.base-url", () -> 공급사B.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.b.api-key", () -> "demo-b-key");
        registry.add("stay.supplier.b.response-timeout", () -> "300ms");
        registry.add("stay.supplier.b.connect-timeout", () -> "300ms");
        registry.add("stay.search.budget", () -> "2s");
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
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (A재고경로.equals(request.getRequestUrl().encodedPath())) {
                    return json응답(A재고본문(요청코드(request, "hotelCodes")));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
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
        List<String> items = codes.stream().map(code -> A재고항목(code, "2026-09", 120000, 12000)).toList();
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String A재고항목(String code, String 연월, long 첫박요금, long 첫박세금) {
        return """
                {"hotelCode":"%s","hotelName":"Demo Hotel %s","roomTypeCode":"DLX-TWN","roomTypeName":"Deluxe Twin",\
                "maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW","dailyRates":[\
                {"date":"%s-20","remainingRooms":3,"nightlyRate":%d,"taxAmount":%d},\
                {"date":"%s-21","remainingRooms":1,"nightlyRate":150000,"taxAmount":15000},\
                {"date":"%s-22","remainingRooms":5,"nightlyRate":120000,"taxAmount":12000}]}"""
                .formatted(code, code, 연월, 첫박요금, 첫박세금, 연월, 연월);
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

package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공급사 A 항목 검증")
class StaySearchItemValidationTest {

    private static final String 검색경로 = "/api/v1/stays/search";
    private static final String 숙소목록본문 =
            """
            {
              "items": [
                {
                  "hotelCode": "A-10023",
                  "hotelName": "Riverside Hotel Seoul",
                  "roomTypes": [
                    { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 }
                  ]
                },
                {
                  "hotelCode": "A-10044",
                  "hotelName": "Namsan Garden Stay",
                  "roomTypes": [
                    { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 }
                  ]
                }
              ]
            }
            """;
    private static final String 정상_DLX_TWN =
            """
            {
              "hotelCode": "A-10023",
              "hotelName": "Riverside Hotel Seoul",
              "roomTypeCode": "DLX-TWN",
              "roomTypeName": "Deluxe Twin",
              "maxOccupancy": 2,
              "breakfastIncluded": false,
              "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-20", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                { "date": "2026-09-21", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                { "date": "2026-09-22", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
              ]
            }
            """;
    private static final String 날짜빠짐_STD_DBL =
            """
            {
              "hotelCode": "A-10044",
              "hotelName": "Namsan Garden Stay",
              "roomTypeCode": "STD-DBL",
              "roomTypeName": "Standard Double",
              "maxOccupancy": 2,
              "breakfastIncluded": true,
              "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-20", "remainingRooms": 2, "nightlyRate": 100000, "taxAmount": 10000 },
                { "date": "2026-09-22", "remainingRooms": 4, "nightlyRate": 100000, "taxAmount": 10000 }
              ]
            }
            """;
    private static final String 음수재고_STD_DBL =
            """
            {
              "hotelCode": "A-10044",
              "hotelName": "Namsan Garden Stay",
              "roomTypeCode": "STD-DBL",
              "roomTypeName": "Standard Double",
              "maxOccupancy": 2,
              "breakfastIncluded": true,
              "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-20", "remainingRooms": -1, "nightlyRate": 100000, "taxAmount": 10000 },
                { "date": "2026-09-21", "remainingRooms": 0, "nightlyRate": 110000, "taxAmount": 11000 },
                { "date": "2026-09-22", "remainingRooms": 4, "nightlyRate": 100000, "taxAmount": 10000 }
              ]
            }
            """;
    private static final String 매핑없음_객실 =
            """
            {
              "hotelCode": "A-10023",
              "hotelName": "Riverside Hotel Seoul",
              "roomTypeCode": "UNMAPPED-RT",
              "roomTypeName": "Unknown Suite",
              "maxOccupancy": 2,
              "breakfastIncluded": false,
              "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-20", "remainingRooms": 2, "nightlyRate": 90000, "taxAmount": 9000 },
                { "date": "2026-09-21", "remainingRooms": 2, "nightlyRate": 90000, "taxAmount": 9000 },
                { "date": "2026-09-22", "remainingRooms": 2, "nightlyRate": 90000, "taxAmount": 9000 }
              ]
            }
            """;

    private static final MockWebServer 공급사A = new MockWebServer();
    private static final AtomicReference<String> 재고요금본문 = new AtomicReference<>(itemsJson(정상_DLX_TWN));

    static {
        try {
            공급사A.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MappingStore.MappingUpsertService mappingUpsertService;

    @Autowired
    SupplierCatalogPort catalogPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-item-validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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
    static void 공급사A를_종료한다() throws IOException {
        공급사A.shutdown();
    }

    @BeforeEach
    void 매핑과_공급사응답을_준비한다() throws InterruptedException {
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("/a/v1/hotels".equals(path)) {
                    return json응답(숙소목록본문);
                }
                if ("/a/v1/availability".equals(path)) {
                    return json응답(재고요금본문.get());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        SupplierCatalog catalog = catalogPort.fetchCatalog().block();
        mappingUpsertService.apply(catalog);
        jdbcTemplate.update("delete from mapping_unmapped_code_backoff");
        while (공급사A.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 목록 조회 기록을 비워 검색 요청만 남긴다
        }
    }

    @Test
    @DisplayName("날짜가 빠진 항목은 없고 다른 정상이 있으면 200이며 failedSuppliers는 비다")
    void 날짜빠짐_혼합은_정상만_200() throws Exception {
        재고요금본문.set(itemsJson(정상_DLX_TWN, 날짜빠짐_STD_DBL));

        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andExpect(jsonPath("$.properties.length()").value(1))
                .andExpect(jsonPath("$.properties[0].propertyName").value("Riverside Hotel Seoul"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소존재(json, "Namsan Garden Stay"), equalTo(false));
        제외사유가_없다(json);
    }

    @Test
    @DisplayName("음수 재고 항목은 제외되고 정상과 섞이면 200이며 failedSuppliers는 비다")
    void 음수재고_혼합은_정상만_200() throws Exception {
        재고요금본문.set(itemsJson(정상_DLX_TWN, 음수재고_STD_DBL));

        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andExpect(jsonPath("$.properties.length()").value(1))
                .andExpect(jsonPath("$.properties[0].propertyName").value("Riverside Hotel Seoul"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소존재(json, "Namsan Garden Stay"), equalTo(false));
        제외사유가_없다(json);
    }

    @Test
    @DisplayName("항목이 전부 규약 위반이면 검색은 503이고 failedSuppliers[0].supplier는 A다")
    void 전항목_위반은_503() throws Exception {
        재고요금본문.set(itemsJson(음수재고_STD_DBL));

        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("A"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andExpect(jsonPath("$.failedSuppliers[0].exclusionReason").doesNotExist());
    }

    @Test
    @DisplayName("매핑 없는 객실 타입은 응답에 없고 requestForUnmapped가 그 코드로 호출된다")
    void 매핑없는_객실타입은_제외하고_이벤트를_건다() throws Exception {
        재고요금본문.set(itemsJson(정상_DLX_TWN, 매핑없음_객실));

        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andExpect(jsonPath("$.properties.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json, not(containsString("UNMAPPED-RT")));
        제외사유가_없다(json);

        Integer 백오프 = jdbcTemplate.queryForObject(
                """
                select count(*) from mapping_unmapped_code_backoff
                 where supplier = 'A'
                   and supplier_property_code = 'A-10023'
                   and supplier_room_type_code = 'UNMAPPED-RT'
                """,
                Integer.class);
        assertThat(백오프, greaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("매핑 없는 항목만 있으면 503이고 동기화 이벤트가 걸린다")
    void 전항목_매핑없으면_503() throws Exception {
        재고요금본문.set(itemsJson(매핑없음_객실));

        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("A"));

        Integer 백오프 = jdbcTemplate.queryForObject(
                """
                select count(*) from mapping_unmapped_code_backoff
                 where supplier = 'A'
                   and supplier_property_code = 'A-10023'
                   and supplier_room_type_code = 'UNMAPPED-RT'
                """,
                Integer.class);
        assertThat(백오프, greaterThanOrEqualTo(1));
    }

    private org.springframework.test.web.servlet.ResultActions 검색한다() throws Exception {
        return mockMvc.perform(get(검색경로)
                .param("checkIn", "2026-09-20")
                .param("checkOut", "2026-09-23")
                .param("adults", "2")
                .param("children", "0"));
    }

    private static void 제외사유가_없다(String json) {
        assertThat(json, not(containsString("exclusionReason")));
        assertThat(json, not(containsString("excluded")));
        assertThat(json, not(containsString("제외")));
    }

    private static boolean 숙소존재(String json, String 숙소명) {
        Object value = JsonPath.read(json, "$.properties[?(@.propertyName == '" + 숙소명 + "')]");
        return value instanceof List<?> list && !list.isEmpty();
    }

    private static String itemsJson(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static MockResponse json응답(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body);
    }
}

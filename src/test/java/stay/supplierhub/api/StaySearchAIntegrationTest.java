package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공급사 A 검색 연동")
class StaySearchAIntegrationTest {

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
                  "hotelName": "Old Name",
                  "roomTypes": [
                    { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 }
                  ]
                }
              ]
            }
            """;
    private static final String 재고요금본문 =
            """
            {
              "items": [
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
                },
                {
                  "hotelCode": "A-10044",
                  "hotelName": "Namsan Garden Stay",
                  "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double",
                  "maxOccupancy": 4,
                  "breakfastIncluded": true,
                  "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-20", "remainingRooms": 2, "nightlyRate": 100000, "taxAmount": 10000 },
                    { "date": "2026-09-21", "remainingRooms": 0, "nightlyRate": 110000, "taxAmount": 11000 },
                    { "date": "2026-09-22", "remainingRooms": 4, "nightlyRate": 100000, "taxAmount": 10000 }
                  ]
                }
              ]
            }
            """;

    private static final MockWebServer 공급사A = new MockWebServer();

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
    MappingStore.MappingSnapshotHolder snapshotHolder;

    @Autowired
    List<SupplierCatalogPort> catalogPorts;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.supplier.b.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.b.api-key", () -> "demo-b-key");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-a;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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
    void 공급사A응답을_준비한다() throws InterruptedException {
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("/a/v1/hotels".equals(path)) {
                    return json응답(숙소목록본문);
                }
                if ("/a/v1/availability".equals(path)) {
                    return json응답(재고요금본문);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        SupplierCatalog catalog = 공급사A카탈로그().fetchCatalog().block();
        mappingUpsertService.apply(catalog);
        while (공급사A.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 목록 조회 기록을 비워 검색 요청만 남긴다
        }
    }

    @Test
    @DisplayName("A 숙소 검색은 세금포함 기간 총액 429000과 기간 최소 재고 1을 반환한다")
    void A검색_세금포함총액과_기간최소재고() throws Exception {
        String json = mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties[?(@.propertyName == 'Riverside Hotel Seoul')].roomTypes[0].offers[0].supplier")
                        .value("A"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소필드(json, "Riverside Hotel Seoul", "roomTypes[0].offers[0].totalPrice"), equalTo(429000));
        assertThat(숙소필드(json, "Riverside Hotel Seoul", "roomTypes[0].offers[0].currency"), equalTo("KRW"));
        assertThat(숙소필드(json, "Riverside Hotel Seoul", "roomTypes[0].offers[0].availableRooms"), equalTo(1));
        assertThat(숙소필드(json, "Riverside Hotel Seoul", "roomTypes[0].offers[0].breakfastIncluded"), equalTo(false));

        RecordedRequest 목록요청 = 공급사A.takeRequest();
        RecordedRequest 재고요청 = 공급사A.takeRequest(1, TimeUnit.MILLISECONDS);
        if (재고요청 == null) {
            재고요청 = 목록요청;
        } else if (!"/a/v1/availability".equals(재고요청.getRequestUrl().encodedPath())
                && "/a/v1/availability".equals(목록요청.getRequestUrl().encodedPath())) {
            재고요청 = 목록요청;
        }
        assertThat(재고요청.getRequestUrl().encodedPath(), equalTo("/a/v1/availability"));
        assertThat(재고요청.getRequestUrl().queryParameter("hotelCodes"), containsString("A-10023"));
        assertThat(재고요청.getRequestUrl().queryParameter("checkIn"), equalTo("2026-09-20"));
        assertThat(재고요청.getRequestUrl().queryParameter("checkOut"), equalTo("2026-09-23"));
        assertThat(재고요청.getRequestUrl().queryParameter("adults"), equalTo("2"));
        assertThat(재고요청.getRequestUrl().queryParameter("children"), equalTo("0"));
        assertThat(재고요청.getHeader("X-Api-Key"), equalTo("demo-a-key"));
    }

    @Test
    @DisplayName("재고 0인 Namsan 오퍼는 남고 이름과 인원은 응답에서만 덮어쓴다")
    void 재고0_오퍼유지와_이름인원덮어쓰기() throws Exception {
        String json = mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(숙소필드(json, "Namsan Garden Stay", "roomTypes[0].offers[0].availableRooms"), equalTo(0));
        assertThat(숙소필드(json, "Namsan Garden Stay", "roomTypes[0].offers[0].totalPrice"), equalTo(341000));
        assertThat(숙소필드(json, "Namsan Garden Stay", "roomTypes[0].offers[0].breakfastIncluded"), equalTo(true));
        assertThat(숙소필드(json, "Namsan Garden Stay", "roomTypes[0].maxOccupancy"), equalTo(4));
        assertThat(숙소필드(json, "Namsan Garden Stay", "propertyName"), equalTo("Namsan Garden Stay"));

        Number 리버사이드Id = 숙소필드(json, "Riverside Hotel Seoul", "propertyId");
        Number 남산Id = 숙소필드(json, "Namsan Garden Stay", "propertyId");
        assertThat(리버사이드Id.longValue(), not(equalTo(남산Id.longValue())));

        String 매핑숙소명 = jdbcTemplate.queryForObject(
                "select name from property where supplier = ? and supplier_property_code = ?",
                String.class,
                "A",
                "A-10044");
        assertThat(매핑숙소명, equalTo("Old Name"));
        Integer 매핑최대인원 = jdbcTemplate.queryForObject(
                """
                select rt.max_occupancy
                  from room_type rt
                  join property p on rt.property_id = p.id
                 where p.supplier = ?
                   and p.supplier_property_code = ?
                   and rt.supplier_room_type_code = ?
                """,
                Integer.class,
                "A",
                "A-10044",
                "STD-DBL");
        assertThat(매핑최대인원, equalTo(2));

        var 스냅샷매핑 = snapshotHolder
                .current()
                .find(new SupplierId("A"), "A-10044", "STD-DBL")
                .orElseThrow();
        assertThat(스냅샷매핑.propertyName(), equalTo("Old Name"));
        assertThat(스냅샷매핑.maxOccupancy(), equalTo(2));
    }

    @SuppressWarnings("unchecked")
    private static <T> T 숙소필드(String json, String 숙소명, String 상대경로) {
        Object value = JsonPath.read(json, "$.properties[?(@.propertyName == '" + 숙소명 + "')]." + 상대경로);
        if (value instanceof List<?> list) {
            return (T) list.getFirst();
        }
        return (T) value;
    }

    private SupplierCatalogPort 공급사A카탈로그() {
        return catalogPorts.stream()
                .filter(port -> "A".equals(port.supplierId().value()))
                .findFirst()
                .orElseThrow();
    }

    private static MockResponse json응답(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body);
    }
}

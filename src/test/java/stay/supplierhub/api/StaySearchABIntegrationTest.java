package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공급사 A와 B 검색 연동")
class StaySearchABIntegrationTest {

    private static final String 검색경로 = "/api/v1/stays/search";
    private static final String B키 = "demo-b-key";
    private static final String A숙소목록본문 =
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
    private static final String A재고요금본문 =
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
    private static final String B숙소목록본문 =
            """
            {
              "resultCode": "0000",
              "resultMessage": "SUCCESS",
              "data": {
                "items": [
                  {
                    "propertyId": "B77120",
                    "propertyName": "Riverside Hotel Seoul",
                    "rooms": [
                      { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 }
                    ]
                  }
                ]
              }
            }
            """;
    private static final String B재고요금본문 =
            """
            {
              "resultCode": "0000",
              "resultMessage": "SUCCESS",
              "data": {
                "items": [
                  {
                    "propertyId": "B77120",
                    "propertyName": "Riverside Hotel Seoul",
                    "roomId": "R-401",
                    "roomName": "Deluxe Twin Room",
                    "maxOccupancy": 2,
                    "breakfastIncluded": true,
                    "currency": "KRW",
                    "totalPrice": 452000,
                    "taxIncluded": true,
                    "inventory": [
                      { "date": "2026-09-20", "remainingRooms": 3 },
                      { "date": "2026-09-21", "remainingRooms": 1 },
                      { "date": "2026-09-22", "remainingRooms": 5 }
                    ]
                  }
                ]
              }
            }
            """;
    private static final String B장애본문 =
            """
            {
              "resultCode": "E503",
              "resultMessage": "TEMPORARILY_UNAVAILABLE",
              "data": null
            }
            """;

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

    @Autowired
    List<SupplierCatalogPort> catalogPorts;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
        registry.add("stay.supplier.a.response-timeout", () -> "200ms");
        registry.add("stay.supplier.a.connect-timeout", () -> "200ms");
        registry.add("stay.supplier.b.base-url", () -> 공급사B.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.b.api-key", () -> B키);
        registry.add("stay.supplier.b.response-timeout", () -> "200ms");
        registry.add("stay.supplier.b.connect-timeout", () -> "200ms");
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-ab;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("/a/v1/hotels".equals(path)) {
                    return json응답(A숙소목록본문);
                }
                if ("/a/v1/availability".equals(path)) {
                    return json응답(A재고요금본문);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        공급사B.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if ("/b/api/properties".equals(path)) {
                    return json응답(B숙소목록본문);
                }
                if ("/b/api/search".equals(path)) {
                    return json응답(B재고요금본문);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        for (SupplierCatalogPort port : catalogPorts) {
            SupplierCatalog catalog = port.fetchCatalog().block();
            mappingUpsertService.apply(catalog);
        }
        while (공급사A.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 목록 조회 기록을 비워 검색 요청만 남긴다
        }
        while (공급사B.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 목록 조회 기록을 비워 검색 요청만 남긴다
        }
    }

    @Test
    @DisplayName("한 검색이 A 429000과 B 452000을 서로 다른 propertyId로 돌려준다")
    void A와_B를_한_검색에서_서로_다른_ID로_받는다() throws Exception {
        String json = mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> A오퍼묶음 = 공급사오퍼(json, "A", 429000L);
        Map<String, Object> B오퍼묶음 = 공급사오퍼(json, "B", 452000L);
        Map<String, Object> A오퍼 = 맵(A오퍼묶음.get("offer"));
        Map<String, Object> B오퍼 = 맵(B오퍼묶음.get("offer"));
        Map<String, Object> A숙소 = 맵(A오퍼묶음.get("property"));
        Map<String, Object> B숙소 = 맵(B오퍼묶음.get("property"));
        Map<String, Object> A객실 = 맵(A오퍼묶음.get("roomType"));
        Map<String, Object> B객실 = 맵(B오퍼묶음.get("roomType"));

        assertThat(((Number) A오퍼.get("totalPrice")).longValue(), equalTo(429000L));
        assertThat((Boolean) A오퍼.get("breakfastIncluded"), equalTo(false));
        assertThat(((Number) B오퍼.get("totalPrice")).longValue(), equalTo(452000L));
        assertThat((Boolean) B오퍼.get("breakfastIncluded"), equalTo(true));
        assertThat(((Number) B오퍼.get("availableRooms")).intValue(), equalTo(1));
        assertThat(((Number) A숙소.get("propertyId")).longValue(), not(equalTo(((Number) B숙소.get("propertyId")).longValue())));
        assertThat(((Number) A객실.get("roomTypeId")).longValue(), not(equalTo(((Number) B객실.get("roomTypeId")).longValue())));

        RecordedRequest B검색 = 공급사B.takeRequest(200, TimeUnit.MILLISECONDS);
        assertThat(B검색, notNullValue());
        assertThat(B검색.getRequestUrl().encodedPath(), equalTo("/b/api/search"));
        assertThat(B검색.getRequestUrl().queryParameter("propertyIds"), containsString("B77120"));
        assertThat(B검색.getRequestUrl().queryParameter("checkIn"), equalTo("2026-09-20"));
        assertThat(B검색.getRequestUrl().queryParameter("checkOut"), equalTo("2026-09-23"));
        assertThat(B검색.getRequestUrl().queryParameter("adults"), equalTo("2"));
        assertThat(B검색.getRequestUrl().queryParameter("children"), equalTo("0"));
        assertThat(B검색.getHeader("X-Api-Key"), equalTo(B키));
        assertThat(B검색.getHeader("Authorization"), equalTo(null));
    }

    @Test
    @DisplayName("B만 resultCode E503이면 200이고 A 오퍼가 있으며 failedSuppliers는 B다")
    void B만_실패하면_200에_A와_failedB() throws Exception {
        공급사B검색을(json응답(B장애본문));
        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("B"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andExpect(jsonPath("$.failedSuppliers[0].resultMessage").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json, not(containsString("resultMessage")));
        공급사오퍼(json, "A", 429000L);
    }

    @Test
    @DisplayName("B availability가 비-2xx이면 200이고 failedSuppliers는 B다")
    void B가_비2xx이면_200에_A와_failedB() throws Exception {
        공급사B검색을(new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}"));
        검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("B"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist());
    }

    @Test
    @DisplayName("B availability가 JSON이 아니면 200이고 failedSuppliers는 B다")
    void B가_비JSON이면_200에_A와_failedB() throws Exception {
        공급사B검색을(new MockResponse().setHeader("Content-Type", "text/plain").setBody("not-json"));
        검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("B"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist());
    }

    @Test
    @DisplayName("A와 B availability가 모두 실패하면 503 problem+json 이고 Retry-After가 없다")
    void A와_B가_모두_실패하면_503이다() throws Exception {
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/a/v1/availability".equals(request.getRequestUrl().encodedPath())) {
                    return new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        공급사B검색을(json응답(B장애본문));
        검색한다()
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.failedSuppliers[*].supplier", hasItems("A", "B")))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("A availability HTTP 500이고 B가 성공이면 200이고 failedSuppliers는 A다")
    void A실패_B성공은_200에_failedA() throws Exception {
        공급사A.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/a/v1/availability".equals(request.getRequestUrl().encodedPath())) {
                    return new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        String json = 검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("A"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json, not(containsString("\"status\":500")));
        공급사오퍼(json, "B", 452000L);
    }

    @Test
    @DisplayName("매핑이 없어 아무도 호출하지 않으면 200 빈 골격이다")
    void 매핑없으면_200_빈골격() throws Exception {
        snapshotHolder.replace(MappingStore.MappingSnapshot.readyEmpty());
        검색한다()
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.properties").isArray())
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 공급사오퍼(String json, String supplier, long totalPrice) {
        List<Map<String, Object>> properties = com.jayway.jsonpath.JsonPath.read(json, "$.properties");
        for (Map<String, Object> property : properties) {
            List<Map<String, Object>> roomTypes = (List<Map<String, Object>>) property.get("roomTypes");
            if (roomTypes == null) {
                continue;
            }
            for (Map<String, Object> roomType : roomTypes) {
                List<Map<String, Object>> offers = (List<Map<String, Object>>) roomType.get("offers");
                if (offers == null) {
                    continue;
                }
                for (Map<String, Object> offer : offers) {
                    if (!supplier.equals(offer.get("supplier"))) {
                        continue;
                    }
                    Object price = offer.get("totalPrice");
                    if (price instanceof Number number && number.longValue() == totalPrice) {
                        return Map.of("property", property, "roomType", roomType, "offer", offer);
                    }
                }
            }
        }
        throw new AssertionError("공급사 " + supplier + " 오퍼 " + totalPrice + "가 없다: " + json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 맵(Object value) {
        return (Map<String, Object>) value;
    }

    private org.springframework.test.web.servlet.ResultActions 검색한다() throws Exception {
        return mockMvc.perform(get(검색경로)
                .param("checkIn", "2026-09-20")
                .param("checkOut", "2026-09-23")
                .param("adults", "2")
                .param("children", "0"));
    }

    private static void 공급사B검색을(MockResponse 응답) {
        공급사B.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/b/api/search".equals(request.getRequestUrl().encodedPath())) {
                    return 응답;
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    private static MockResponse json응답(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body);
    }
}

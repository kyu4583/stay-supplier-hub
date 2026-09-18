package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;

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
    SupplierCatalogPort catalogPort;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.supplier.a.base-url", () -> 공급사A.url("/").toString().replaceAll("/$", ""));
        registry.add("stay.supplier.a.api-key", () -> "demo-a-key");
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
    void 공급사A응답을_준비한다() {
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
        SupplierCatalog catalog = catalogPort.fetchCatalog().block();
        mappingUpsertService.apply(catalog);
    }

    @Test
    @DisplayName("A 숙소 검색은 세금포함 기간 총액 429000과 기간 최소 재고 1을 반환한다")
    void A검색_세금포함총액과_기간최소재고() throws Exception {
        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-23")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.properties.length()").value(1))
                .andExpect(jsonPath("$.properties[0].propertyId").isNumber())
                .andExpect(jsonPath("$.properties[0].roomTypes[0].roomTypeId").isNumber())
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].supplier").value("A"))
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].totalPrice").value(429000))
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].currency").value("KRW"))
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].availableRooms").value(1))
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].breakfastIncluded").value(false))
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].dailyRates").doesNotExist())
                .andExpect(jsonPath("$.properties[0].roomTypes[0].offers[0].nightlyRate").doesNotExist());

        RecordedRequest 목록요청 = 공급사A.takeRequest();
        RecordedRequest 재고요청 = 공급사A.takeRequest();
        if (!"/a/v1/availability".equals(재고요청.getRequestUrl().encodedPath())
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

    private static MockResponse json응답(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body);
    }
}

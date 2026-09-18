package stay.supplierhub.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("숙소 검색 OpenAPI")
class StaySearchOpenApiTest {

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.mapping.sync-on-startup", () -> "false");
    }

    @Test
    @DisplayName("/v3/api-docs 에 availableRooms 비가산성과 0의 뜻이 있다")
    void availableRooms_설명이_문서에_있다() throws Exception {
        String 문서 = 문서를_가져온다();

        assertThat(문서.contains("그 오퍼로 요청 기간 전체를 예약할 수 있는 객실 수"), equalTo(true));
        assertThat(문서.contains("오퍼끼리 더할 수 없다"), equalTo(true));
        assertThat(문서.contains("0이면 요청 기간 중 어느 날 실제로 매진이라 예약할 수 없다"), equalTo(true));
    }

    @Test
    @DisplayName("/v3/api-docs 에 failedSuppliers 항목 뜻이 있다")
    void failedSuppliers_설명이_문서에_있다() throws Exception {
        String 문서 = 문서를_가져온다();

        assertThat(문서.contains("결과의 일부 또는 전부가 빠진 공급사"), equalTo(true));
        assertThat(문서.contains("없으면 빈 배열"), equalTo(true));
        assertThat(문서.contains("항목은 supplier 코드를 담은 객체"), equalTo(true));
        assertThat(문서.contains("실패 사유는 담지 않는다"), equalTo(true));
    }

    @Test
    @DisplayName("propertyId와 roomTypeId 스키마 타입은 string이 아니다")
    void 식별자_스키마는_문자열이_아니다() throws Exception {
        String 문서 = 문서를_가져온다();

        List<String> propertyId타입 = JsonPath.read(문서, "$..properties.propertyId.type");
        List<String> roomTypeId타입 = JsonPath.read(문서, "$..properties.roomTypeId.type");

        assertThat(propertyId타입, not(empty()));
        assertThat(roomTypeId타입, not(empty()));
        assertThat(propertyId타입.contains("string"), equalTo(false));
        assertThat(roomTypeId타입.contains("string"), equalTo(false));
    }

    @Test
    @DisplayName("failedSuppliers 항목은 object이다")
    void failedSuppliers_항목은_객체이다() throws Exception {
        String 문서 = 문서를_가져온다();
        List<Map<String, Object>> 항목목록 = JsonPath.read(문서, "$..properties.failedSuppliers.items");

        assertThat(항목목록, not(empty()));
        for (Map<String, Object> 항목 : 항목목록) {
            assertThat(항목타입(문서, 항목), equalTo("object"));
        }
    }

    @Test
    @DisplayName("오퍼 스키마에 예약가능 boolean 필드가 없다")
    void 오퍼에_예약가능_불리언이_없다() throws Exception {
        String 문서 = 문서를_가져온다();
        Map<String, Object> 스키마들 = JsonPath.read(문서, "$.components.schemas");
        Map<String, Object> 오퍼속성 = null;
        for (Object 스키마 : 스키마들.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> 속성 =
                    (Map<String, Object>) ((Map<String, Object>) 스키마).get("properties");
            if (속성 != null && 속성.containsKey("availableRooms")) {
                오퍼속성 = 속성;
                break;
            }
        }

        assertThat(오퍼속성, notNullValue());
        assertThat(오퍼속성.containsKey("available"), equalTo(false));
        assertThat(오퍼속성.containsKey("availability"), equalTo(false));
        assertThat(오퍼속성.containsKey("bookable"), equalTo(false));
        assertThat(오퍼속성.containsKey("reservable"), equalTo(false));
    }

    private String 문서를_가져온다() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String 항목타입(String 문서, Map<String, Object> 항목) {
        Object 타입 = 항목.get("type");
        if (타입 != null) {
            return String.valueOf(타입);
        }
        Object 참조 = 항목.get("$ref");
        if (참조 == null) {
            throw new AssertionError("failedSuppliers items에 type 또는 $ref가 없다: " + 항목);
        }
        String 경로 = String.valueOf(참조);
        String 이름 = 경로.substring(경로.lastIndexOf('/') + 1);
        return JsonPath.read(문서, "$.components.schemas['" + 이름 + "'].type");
    }
}

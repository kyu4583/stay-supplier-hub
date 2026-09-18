package stay.supplierhub.api;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("숙소 검색 API")
class StaySearchControllerTest {

    private static final String 검색경로 = "/api/v1/stays/search";

    @Autowired
    MockMvc mockMvc;

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

    @Test
    @DisplayName("유효한 검색은 200이고 properties와 failedSuppliers가 빈 배열이다")
    void 유효한_검색은_빈_골격을_반환한다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-09-22", "2", "0"));
    }

    @Test
    @DisplayName("체크아웃이 체크인과 같으면 400 problem+json 이다")
    void 체크아웃이_체크인과_같으면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-20", "2026-09-20", "2", "0"));
    }

    @Test
    @DisplayName("인증 헤더 없이 유효 검색이 200이다")
    void 인증_없이_유효_검색이_200이다() throws Exception {
        빈골격_200을_검증한다(
                mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-22")
                        .param("adults", "2")
                        .param("children", "0")));
    }

    @Test
    @DisplayName("체크인이 없으면 400 problem+json 이다")
    void 체크인이_없으면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다(null, "2026-09-22", "2", "0"));
    }

    @Test
    @DisplayName("날짜가 YYYY-MM-DD가 아니면 400 problem+json 이다")
    void 날짜_형식이_아니면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026/09/20", "2026-09-22", "2", "0"));
    }

    @Test
    @DisplayName("체크아웃이 체크인보다 이전이면 400이다")
    void 체크아웃이_체크인보다_이전이면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-22", "2026-09-20", "2", "0"));
    }

    @Test
    @DisplayName("체크인이 오늘이면 허용한다")
    void 체크인이_오늘이면_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-19", "2026-09-20", "2", "0"));
    }

    @Test
    @DisplayName("체크인이 어제면 400이다")
    void 체크인이_어제면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-18", "2026-09-20", "2", "0"));
    }

    @Test
    @DisplayName("성인이 0이면 400이다")
    void 성인이_0이면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-20", "2026-09-22", "0", "0"));
    }

    @Test
    @DisplayName("아동이 음수이면 400이다")
    void 아동이_음수이면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-20", "2026-09-22", "1", "-1"));
    }

    @Test
    @DisplayName("성인 1명 아동 0명이면 200이다")
    void 성인1_아동0이면_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-09-22", "1", "0"));
    }

    @Test
    @DisplayName("31박이면 400이다")
    void 삼십일박이면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-20", "2026-10-21", "2", "0"));
    }

    @Test
    @DisplayName("30박이면 200이다")
    void 삼십박이면_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-10-20", "2", "0"));
    }

    @Test
    @DisplayName("체크인이 오늘부터 181일이면 400이다")
    void 체크인이_181일_후면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2027-03-19", "2027-03-20", "2", "0"));
    }

    @Test
    @DisplayName("체크인이 오늘부터 180일이면 200이다")
    void 체크인이_180일_후면_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2027-03-18", "2027-03-19", "2", "0"));
    }

    @Test
    @DisplayName("성인+아동이 37명이면 400이다")
    void 인원_37명이면_400이다() throws Exception {
        문제상세_400을_검증한다(검색한다("2026-09-20", "2026-09-22", "37", "0"));
    }

    @Test
    @DisplayName("성인+아동이 36명이면 200이다")
    void 인원_36명이면_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-09-22", "36", "0"));
    }

    @Test
    @DisplayName("겹치는 유효 검색 두 건은 각각 200 빈 골격이다")
    void 겹치는_검색도_각각_200이다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-09-22", "2", "0"));
        빈골격_200을_검증한다(검색한다("2026-09-21", "2026-09-23", "1", "0"));
    }

    @Test
    @DisplayName("유효 검색은 503이 아니고 Retry-After가 없다")
    void 유효_검색은_503이_아니다() throws Exception {
        빈골격_200을_검증한다(검색한다("2026-09-20", "2026-09-22", "2", "0"));
    }

    private ResultActions 검색한다(String checkIn, String checkOut, String adults, String children)
            throws Exception {
        var 요청 = get(검색경로);
        if (checkIn != null) {
            요청.param("checkIn", checkIn);
        }
        if (checkOut != null) {
            요청.param("checkOut", checkOut);
        }
        if (adults != null) {
            요청.param("adults", adults);
        }
        if (children != null) {
            요청.param("children", children);
        }
        return mockMvc.perform(요청);
    }

    private ResultActions 빈골격_200을_검증한다(ResultActions 결과) throws Exception {
        결과.andExpect(status().isOk())
                .andExpect(status().is(not(503)))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.properties").isArray())
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0));
        return 결과;
    }

    private void 문제상세_400을_검증한다(ResultActions 결과) throws Exception {
        결과.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").exists())
                .andExpect(header().doesNotExist("Retry-After"));
    }
}

package stay.supplierhub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    private ResultActions 검색한다(String checkIn, String checkOut, String adults, String children)
            throws Exception {
        return mockMvc.perform(get(검색경로)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .param("adults", adults)
                .param("children", children));
    }

    private void 빈골격_200을_검증한다(ResultActions 결과) throws Exception {
        결과.andExpect(status().isOk())
                .andExpect(jsonPath("$.properties").isArray())
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0));
    }

    private void 문제상세_400을_검증한다(ResultActions 결과) throws Exception {
        결과.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").exists());
    }
}

package stay.supplierhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import stay.supplierhub.mapping.MappingStore;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;
import stay.supplierhub.search.SupplierRegistry;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("검색 매핑 준비 상태")
class StaySearchReadinessTest {

    private static final String 검색경로 = "/api/v1/stays/search";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MappingStore.MappingSnapshotHolder snapshotHolder;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:stay-search-readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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

    @Test
    @DisplayName("한 번도 동기화하지 못한 A는 503 problem+json 이고 Retry-After가 없다")
    void 미준비_A는_503이다() throws Exception {
        snapshotHolder.replace(MappingStore.MappingSnapshot.unready());
        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-22")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").exists())
                .andExpect(jsonPath("$.failedSuppliers[*].supplier").value(hasItem("A")))
                .andExpect(jsonPath("$.failedSuppliers[*].supplier").value(hasItem("B")))
                .andExpect(jsonPath("$.failedSuppliers[*].reason").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("준비됐는데 활성 숙소가 없으면 200 빈 결과다")
    void 준비_활성없음은_200_빈결과() throws Exception {
        snapshotHolder.replace(MappingStore.MappingSnapshot.readyEmpty());
        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-22")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.properties").isArray())
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.failedSuppliers").isArray())
                .andExpect(jsonPath("$.failedSuppliers.length()").value(0));
    }

    @Test
    @DisplayName("A는 준비+활성 코드 없고 B만 미준비면 200이고 failedSuppliers는 B다")
    void 혼합준비_코드없음과_미준비는_200이다() throws Exception {
        snapshotHolder.replace(MappingStore.MappingSnapshot.explicit(
                Set.of(new SupplierId("A")), Set.of(new SupplierId("B"))));
        mockMvc.perform(get(검색경로)
                        .param("checkIn", "2026-09-20")
                        .param("checkOut", "2026-09-22")
                        .param("adults", "2")
                        .param("children", "0"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.properties").isArray())
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.failedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.failedSuppliers[0].supplier").value("B"))
                .andExpect(jsonPath("$.failedSuppliers[0].reason").doesNotExist());
    }

    @Test
    @DisplayName("어댑터 SupplierId가 중복이면 기동에 실패한다")
    void 중복_ID는_기동_실패() {
        new ApplicationContextRunner()
                .withUserConfiguration(SupplierRegistry.class)
                .withBean("catalog1", SupplierCatalogPort.class, () -> 카탈로그("A"))
                .withBean("catalog2", SupplierCatalogPort.class, () -> 카탈로그("A"))
                .withPropertyValues("stay.supplier.a.base-url=http://localhost:9090")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    @DisplayName("설정에 없는 어댑터 ID는 기동에 실패한다")
    void 설정없는_ID는_기동_실패() {
        new ApplicationContextRunner()
                .withUserConfiguration(SupplierRegistry.class)
                .withBean(SupplierCatalogPort.class, () -> 카탈로그("X"))
                .withPropertyValues("stay.supplier.a.base-url=http://localhost:9090")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    private static SupplierCatalogPort 카탈로그(String id) {
        return new SupplierCatalogPort() {
            @Override
            public SupplierId supplierId() {
                return new SupplierId(id);
            }

            @Override
            public Mono<SupplierCatalog> fetchCatalog() {
                return Mono.empty();
            }
        };
    }
}

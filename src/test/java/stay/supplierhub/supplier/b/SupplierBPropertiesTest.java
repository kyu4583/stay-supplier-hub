package stay.supplierhub.supplier.b;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("공급사 B 연동 설정 바인딩")
class SupplierBPropertiesTest {

    private final ApplicationContextRunner 컨텍스트 = new ApplicationContextRunner()
            .withUserConfiguration(공급사설정.class)
            .withPropertyValues(
                    "stay.supplier.b.max-codes-per-call=50",
                    "stay.supplier.b.max-connections=2500",
                    "stay.supplier.b.max-concurrent-calls=8");

    @Test
    @DisplayName("묶음 크기·커넥션 수·동시 호출 수가 양수면 기동한다")
    void 양수_설정() {
        컨텍스트.run(context -> assertThat(context.getStartupFailure(), nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-codes-per-call", "max-connections", "max-concurrent-calls"})
    @DisplayName("묶음 크기·커넥션 수·동시 호출 수 중 하나가 0이면 기동이 실패한다")
    void 영인_설정(String 키) {
        컨텍스트.withPropertyValues("stay.supplier.b." + 키 + "=0")
                .run(context -> assertThat(context.getStartupFailure(), notNullValue()));
    }

    @Configuration
    @EnableConfigurationProperties(SupplierBProperties.class)
    static class 공급사설정 {}
}

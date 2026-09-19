package stay.supplierhub.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("검색 예산 설정 바인딩")
class SearchBudgetPropertiesTest {

    private final ApplicationContextRunner 컨텍스트 =
            new ApplicationContextRunner().withUserConfiguration(예산설정.class);

    @Test
    @DisplayName("양수 예산은 그대로 바인딩된다")
    void 양수_예산() {
        컨텍스트.withPropertyValues("stay.search.budget=3s").run(context -> {
            assertThat(context.getStartupFailure(), nullValue());
            assertThat(context.getBean(SearchBudgetProperties.class).budget(), equalTo(Duration.ofSeconds(3)));
        });
    }

    @Test
    @DisplayName("예산 키가 없으면 기동이 실패한다")
    void 예산_누락() {
        컨텍스트.run(context -> assertThat(context.getStartupFailure(), notNullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    @DisplayName("0이나 음수 예산이면 기동이 실패한다")
    void 잘못된_예산(String 예산) {
        컨텍스트.withPropertyValues("stay.search.budget=" + 예산)
                .run(context -> assertThat(context.getStartupFailure(), notNullValue()));
    }

    @Configuration
    @EnableConfigurationProperties(SearchBudgetProperties.class)
    static class 예산설정 {}
}

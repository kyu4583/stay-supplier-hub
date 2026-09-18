package stay.supplierhub;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StaySupplierHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(StaySupplierHubApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    WebMvcConfigurer problemDetailsTypeConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
                interceptors.add((detail, errorResponse) -> {
                    if (detail.getType() == null) {
                        detail.setType(URI.create("about:blank"));
                    }
                });
            }
        };
    }
}

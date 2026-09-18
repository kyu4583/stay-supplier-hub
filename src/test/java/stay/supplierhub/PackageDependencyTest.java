package stay.supplierhub;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@AnalyzeClasses(packages = "stay.supplierhub", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageDependencyTest {

    @ArchTest
    static final ArchRule search는_supplier_a에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage("stay.supplierhub.search..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("stay.supplierhub.supplier.a..")
            .as("search 패키지는 supplier.a에 의존하지 않는다");

    @ArchTest
    static final ArchRule mapping은_supplier_a에_의존하지_않는다 = noClasses()
            .that()
            .resideInAPackage("stay.supplierhub.mapping..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("stay.supplierhub.supplier.a..")
            .as("mapping 패키지는 supplier.a에 의존하지 않는다");

    @ArchTest
    static final ArchRule 매퍼는_HTTP와_Reactor를_모른다 = noClasses()
            .that()
            .haveSimpleName("SupplierAMapper")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web.reactive.function.client..", "reactor.core.publisher..")
            .as("SupplierAMapper는 WebClient와 Reactor를 쓰지 않는다");

    @ArchTest
    static final ArchRule 어댑터는_block하지_않는다 = noClasses()
            .that()
            .haveSimpleName("SupplierAAdapter")
            .should()
            .callMethod(Mono.class, "block")
            .orShould()
            .callMethod(Mono.class, "block", Duration.class)
            .as("SupplierAAdapter 인스턴스 메서드는 block을 호출하지 않는다");

    @Test
    @DisplayName("SupplierId 타입은 enum이 아니다")
    void SupplierId는_enum이_아니다() {
        assertThat(SupplierId.class.isEnum(), equalTo(false));
    }
}

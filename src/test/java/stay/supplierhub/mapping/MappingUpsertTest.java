package stay.supplierhub.mapping;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@SpringBootTest
@DisplayName("매핑 upsert")
class MappingUpsertTest {

    @Autowired
    MappingStore.MappingUpsertService mappingUpsertService;

    @Autowired
    PropertyRepository propertyRepository;

    @Autowired
    RoomTypeRepository roomTypeRepository;

    @DynamicPropertySource
    static void 테스트속성(DynamicPropertyRegistry registry) {
        registry.add("stay.mapping.sync-on-startup", () -> "false");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:mapping-upsert;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
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
    @DisplayName("같은 공급사 코드는 다시 upsert해도 같은 내부 ID다")
    void 같은_코드는_같은_내부_ID() {
        mappingUpsertService.apply(목록(리버사이드()));
        PropertyEntity 첫번째숙소 = 숙소("A-10023");
        RoomTypeEntity 첫번째객실 = 객실(첫번째숙소, "DLX-TWN");
        long 숙소Id = 첫번째숙소.id;
        long 객실Id = 첫번째객실.id;

        mappingUpsertService.apply(목록(리버사이드()));
        PropertyEntity 두번째숙소 = 숙소("A-10023");
        RoomTypeEntity 두번째객실 = 객실(두번째숙소, "DLX-TWN");

        assertThat(두번째숙소.id, equalTo(숙소Id));
        assertThat(두번째객실.id, equalTo(객실Id));
        assertThat(두번째숙소.active, equalTo(true));
        assertThat(두번째객실.active, equalTo(true));
    }

    @Test
    @DisplayName("목록에서 사라진 숙소는 삭제하지 않고 비활성으로 둔다")
    void 사라진_숙소는_비활성() {
        mappingUpsertService.apply(목록(리버사이드(), 남산()));
        long 리버사이드Id = 숙소("A-10023").id;
        long 남산Id = 숙소("A-10044").id;
        long 남산객실Id = 객실(숙소("A-10044"), "STD-DBL").id;
        long 숙소수 = propertyRepository.count();
        long 객실수 = roomTypeRepository.count();

        mappingUpsertService.apply(목록(리버사이드()));
        PropertyEntity 비활성숙소 = 숙소("A-10044");
        RoomTypeEntity 비활성객실 = 객실(비활성숙소, "STD-DBL");
        assertThat(비활성숙소.id, equalTo(남산Id));
        assertThat(비활성숙소.active, equalTo(false));
        assertThat(비활성객실.id, equalTo(남산객실Id));
        assertThat(비활성객실.active, equalTo(false));
        assertThat(숙소("A-10023").id, equalTo(리버사이드Id));
        assertThat(숙소("A-10023").active, equalTo(true));
        assertThat(propertyRepository.count(), equalTo(숙소수));
        assertThat(roomTypeRepository.count(), equalTo(객실수));

        mappingUpsertService.apply(목록(리버사이드(), 남산()));
        PropertyEntity 재활성숙소 = 숙소("A-10044");
        RoomTypeEntity 재활성객실 = 객실(재활성숙소, "STD-DBL");
        assertThat(재활성숙소.id, equalTo(남산Id));
        assertThat(재활성숙소.active, equalTo(true));
        assertThat(재활성객실.id, equalTo(남산객실Id));
        assertThat(재활성객실.active, equalTo(true));
    }

    private PropertyEntity 숙소(String 숙소코드) {
        PropertyEntity property =
                propertyRepository.findBySupplierAndSupplierPropertyCode("A", 숙소코드).orElse(null);
        assertThat(property, notNullValue());
        return property;
    }

    private RoomTypeEntity 객실(PropertyEntity 숙소, String 객실코드) {
        RoomTypeEntity roomType =
                roomTypeRepository.findByPropertyAndSupplierRoomTypeCode(숙소, 객실코드).orElse(null);
        assertThat(roomType, notNullValue());
        return roomType;
    }

    private static SupplierCatalog 목록(CatalogProperty... 숙소들) {
        return new SupplierCatalog(new SupplierId("A"), List.of(숙소들));
    }

    private static CatalogProperty 리버사이드() {
        return new CatalogProperty(
                "A-10023", "Riverside Hotel Seoul", List.of(new CatalogRoomType("DLX-TWN", "Deluxe Twin", 2)));
    }

    private static CatalogProperty 남산() {
        return new CatalogProperty(
                "A-10044", "Namsan Garden Stay", List.of(new CatalogRoomType("STD-DBL", "Standard Double", 2)));
    }
}

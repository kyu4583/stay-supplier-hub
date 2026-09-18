package stay.supplierhub.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "mapping_supplier_readiness")
class SupplierReadinessEntity {

    @Id
    @Column(nullable = false, length = 32)
    String supplier;

    @Column(nullable = false)
    boolean ready;

    @Column(name = "last_success_at")
    Instant lastSuccessAt;
}

interface SupplierReadinessRepository extends JpaRepository<SupplierReadinessEntity, String> {}

@Entity
@Table(
        name = "mapping_unmapped_code_backoff",
        uniqueConstraints =
                @UniqueConstraint(columnNames = {"supplier", "supplier_property_code", "supplier_room_type_code"}))
class UnmappedCodeBackoffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 32)
    String supplier;

    @Column(name = "supplier_property_code", nullable = false, length = 128)
    String supplierPropertyCode;

    @Column(name = "supplier_room_type_code", nullable = false, length = 128)
    String supplierRoomTypeCode;

    @Column(nullable = false)
    int step;

    @Column(name = "next_eligible_at", nullable = false)
    Instant nextEligibleAt;
}

interface UnmappedCodeBackoffRepository extends JpaRepository<UnmappedCodeBackoffEntity, Long> {

    Optional<UnmappedCodeBackoffEntity> findBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode(
            String supplier, String supplierPropertyCode, String supplierRoomTypeCode);

    void deleteBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode(
            String supplier, String supplierPropertyCode, String supplierRoomTypeCode);
}

public final class MappingSyncState {

    private MappingSyncState() {}
}

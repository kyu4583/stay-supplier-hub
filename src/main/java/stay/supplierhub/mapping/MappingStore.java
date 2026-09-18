package stay.supplierhub.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@ConfigurationProperties(prefix = "stay.mapping")
record MappingProperties(boolean syncOnStartup) {}

@Entity
@Table(
        name = "property",
        uniqueConstraints = @UniqueConstraint(columnNames = {"supplier", "supplier_property_code"}))
class PropertyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String supplier;

    @Column(name = "supplier_property_code", nullable = false)
    String supplierPropertyCode;

    @Column(nullable = false)
    String name;

    @Column(nullable = false)
    boolean active;

    @Column(name = "last_seen_at", nullable = false)
    Instant lastSeenAt;

    @OneToMany(mappedBy = "property", fetch = FetchType.EAGER)
    List<RoomTypeEntity> roomTypes = new ArrayList<>();
}

@Entity
@Table(
        name = "room_type",
        uniqueConstraints = @UniqueConstraint(columnNames = {"property_id", "supplier_room_type_code"}))
class RoomTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    PropertyEntity property;

    @Column(name = "supplier_room_type_code", nullable = false)
    String supplierRoomTypeCode;

    @Column(nullable = false)
    String name;

    @Column(name = "max_occupancy", nullable = false)
    int maxOccupancy;

    @Column(nullable = false)
    boolean active;

    @Column(name = "last_seen_at", nullable = false)
    Instant lastSeenAt;
}

interface PropertyRepository extends JpaRepository<PropertyEntity, Long> {

    Optional<PropertyEntity> findBySupplierAndSupplierPropertyCode(String supplier, String supplierPropertyCode);

    List<PropertyEntity> findByActiveTrue();
}

interface RoomTypeRepository extends JpaRepository<RoomTypeEntity, Long> {

    Optional<RoomTypeEntity> findByPropertyAndSupplierRoomTypeCode(
            PropertyEntity property, String supplierRoomTypeCode);
}

public final class MappingStore {

    private MappingStore() {}

    public record MappedProperty(
            long propertyId,
            String supplier,
            String supplierPropertyCode,
            String name,
            List<MappedRoomType> roomTypes) {}

    public record MappedRoomType(long roomTypeId, String supplierRoomTypeCode, String name, int maxOccupancy) {}

    public record MappedOfferTarget(
            long propertyId, String propertyName, long roomTypeId, String roomTypeName, int maxOccupancy) {}

    public static final class MappingSnapshot {

        private final boolean ready;
        private final List<MappedProperty> properties;
        private final Map<String, MappedProperty> bySupplierAndCode;

        private MappingSnapshot(boolean ready, List<MappedProperty> properties) {
            this.ready = ready;
            this.properties = List.copyOf(properties);
            Map<String, MappedProperty> index = new LinkedHashMap<>();
            for (MappedProperty property : this.properties) {
                index.put(key(property.supplier(), property.supplierPropertyCode()), property);
            }
            this.bySupplierAndCode = Map.copyOf(index);
        }

        public static MappingSnapshot unready() {
            return new MappingSnapshot(false, List.of());
        }

        public static MappingSnapshot readyEmpty() {
            return new MappingSnapshot(true, List.of());
        }

        public static MappingSnapshot ready(List<MappedProperty> properties) {
            return new MappingSnapshot(true, properties);
        }

        public boolean ready() {
            return ready;
        }

        public List<String> activePropertyCodes(SupplierId supplier) {
            String supplierCode = supplier.value();
            return properties.stream()
                    .filter(property -> property.supplier().equals(supplierCode))
                    .map(MappedProperty::supplierPropertyCode)
                    .toList();
        }

        public Optional<MappedOfferTarget> find(
                SupplierId supplier, String supplierPropertyCode, String supplierRoomTypeCode) {
            MappedProperty property = bySupplierAndCode.get(key(supplier.value(), supplierPropertyCode));
            if (property == null) {
                return Optional.empty();
            }
            return property.roomTypes().stream()
                    .filter(roomType -> roomType.supplierRoomTypeCode().equals(supplierRoomTypeCode))
                    .findFirst()
                    .map(roomType -> new MappedOfferTarget(
                            property.propertyId(),
                            property.name(),
                            roomType.roomTypeId(),
                            roomType.name(),
                            roomType.maxOccupancy()));
        }

        private static String key(String supplier, String supplierPropertyCode) {
            return supplier + '\0' + supplierPropertyCode;
        }
    }

    @Component
    public static class MappingSnapshotHolder {

        private final AtomicReference<MappingSnapshot> current =
                new AtomicReference<>(MappingSnapshot.unready());

        public MappingSnapshot current() {
            return current.get();
        }

        public void replace(MappingSnapshot snapshot) {
            current.set(Objects.requireNonNull(snapshot));
        }
    }

    @Service
    public static class MappingUpsertService {

        private final PropertyRepository propertyRepository;
        private final RoomTypeRepository roomTypeRepository;
        private final MappingSnapshotHolder snapshotHolder;
        private final Clock clock;

        public MappingUpsertService(
                PropertyRepository propertyRepository,
                RoomTypeRepository roomTypeRepository,
                MappingSnapshotHolder snapshotHolder,
                Clock clock) {
            this.propertyRepository = propertyRepository;
            this.roomTypeRepository = roomTypeRepository;
            this.snapshotHolder = snapshotHolder;
            this.clock = clock;
        }

        @Transactional
        public void apply(SupplierCatalog catalog) {
            Instant now = Instant.now(clock);
            String supplier = catalog.supplier().value();
            for (CatalogProperty incoming : catalog.properties()) {
                PropertyEntity property = propertyRepository
                        .findBySupplierAndSupplierPropertyCode(supplier, incoming.supplierPropertyCode())
                        .orElseGet(PropertyEntity::new);
                property.supplier = supplier;
                property.supplierPropertyCode = incoming.supplierPropertyCode();
                property.name = incoming.name();
                property.active = true;
                property.lastSeenAt = now;
                propertyRepository.save(property);
                for (CatalogRoomType incomingRoom : incoming.roomTypes()) {
                    RoomTypeEntity roomType = roomTypeRepository
                            .findByPropertyAndSupplierRoomTypeCode(property, incomingRoom.supplierRoomTypeCode())
                            .orElseGet(RoomTypeEntity::new);
                    roomType.property = property;
                    roomType.supplierRoomTypeCode = incomingRoom.supplierRoomTypeCode();
                    roomType.name = incomingRoom.name();
                    roomType.maxOccupancy = incomingRoom.maxOccupancy();
                    roomType.active = true;
                    roomType.lastSeenAt = now;
                    roomTypeRepository.save(roomType);
                    if (property.roomTypes.stream()
                            .noneMatch(existing -> existing.supplierRoomTypeCode.equals(roomType.supplierRoomTypeCode))) {
                        property.roomTypes.add(roomType);
                    }
                }
            }
            propertyRepository.flush();
            snapshotHolder.replace(loadReadySnapshot());
        }

        private MappingSnapshot loadReadySnapshot() {
            List<MappedProperty> mapped = new ArrayList<>();
            for (PropertyEntity property : propertyRepository.findByActiveTrue()) {
                List<MappedRoomType> rooms = property.roomTypes.stream()
                        .filter(roomType -> roomType.active)
                        .map(roomType -> new MappedRoomType(
                                roomType.id, roomType.supplierRoomTypeCode, roomType.name, roomType.maxOccupancy))
                        .toList();
                mapped.add(new MappedProperty(
                        property.id, property.supplier, property.supplierPropertyCode, property.name, rooms));
            }
            return MappingSnapshot.ready(mapped);
        }
    }
}

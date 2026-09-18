package stay.supplierhub.mapping;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import stay.supplierhub.mapping.MappingStore.MappedProperty;
import stay.supplierhub.mapping.MappingStore.MappedRoomType;
import stay.supplierhub.mapping.MappingStore.MappingSnapshot;
import stay.supplierhub.mapping.MappingStore.MappingSnapshotHolder;
import stay.supplierhub.mapping.MappingStore.MappingUpsertService;
import stay.supplierhub.search.SupplierContracts.CatalogProperty;
import stay.supplierhub.search.SupplierContracts.CatalogRoomType;
import stay.supplierhub.search.SupplierContracts.SupplierCatalog;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@ConfigurationProperties(prefix = "stay.mapping")
record MappingProperties(boolean syncOnStartup, Duration syncInterval, double shrinkRejectRatio) {}

public final class MappingSync {

    private MappingSync() {}

    @Component
    public static class PropertyListSynchronizer {

        private static final Logger log = LoggerFactory.getLogger(PropertyListSynchronizer.class);
        private static final List<Duration> BACKOFF_STEPS = List.of(
                Duration.ofMinutes(3),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofMinutes(90),
                Duration.ofMinutes(270));

        private final List<SupplierCatalogPort> catalogPorts;
        private final MappingUpsertService upsertService;
        private final MappingSnapshotHolder snapshotHolder;
        private final MappingProperties properties;
        private final PropertyRepository propertyRepository;
        private final SupplierReadinessRepository readinessRepository;
        private final UnmappedCodeBackoffRepository backoffRepository;
        private final Clock clock;
        private final TransactionTemplate transactionTemplate;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean runAgain = new AtomicBoolean();
        private final ExecutorService syncExecutor =
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("mapping-sync-", 0).factory());

        PropertyListSynchronizer(
                List<SupplierCatalogPort> catalogPorts,
                MappingUpsertService upsertService,
                MappingSnapshotHolder snapshotHolder,
                MappingProperties properties,
                PropertyRepository propertyRepository,
                SupplierReadinessRepository readinessRepository,
                UnmappedCodeBackoffRepository backoffRepository,
                Clock clock,
                PlatformTransactionManager transactionManager) {
            this.catalogPorts = List.copyOf(catalogPorts);
            this.upsertService = upsertService;
            this.snapshotHolder = snapshotHolder;
            this.properties = properties;
            this.propertyRepository = propertyRepository;
            this.readinessRepository = readinessRepository;
            this.backoffRepository = backoffRepository;
            this.clock = clock;
            this.transactionTemplate = new TransactionTemplate(transactionManager);
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            restoreSnapshotFromStore();
            if (properties.syncOnStartup()) {
                runSyncCatching();
            }
        }

        @Scheduled(
                fixedDelayString = "${stay.mapping.sync-interval}",
                initialDelayString = "${stay.mapping.sync-interval}")
        public void onSchedule() {
            if (running.get()) {
                return;
            }
            tryStart();
        }

        public void requestManual() {
            runAgain.set(true);
            tryStart();
        }

        public void requestForUnmapped(SupplierId supplier, String propertyCode, String roomTypeCode) {
            boolean shouldSync = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                if (snapshotHolder.current().find(supplier, propertyCode, roomTypeCode).isPresent()) {
                    backoffRepository.deleteBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode(
                            supplier.value(), propertyCode, roomTypeCode);
                    return false;
                }
                Instant now = Instant.now(clock);
                UnmappedCodeBackoffEntity row = backoffRepository
                        .findBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode(
                                supplier.value(), propertyCode, roomTypeCode)
                        .orElse(null);
                if (row == null) {
                    UnmappedCodeBackoffEntity created = new UnmappedCodeBackoffEntity();
                    created.supplier = supplier.value();
                    created.supplierPropertyCode = propertyCode;
                    created.supplierRoomTypeCode = roomTypeCode;
                    created.step = 0;
                    created.nextEligibleAt = now.plus(BACKOFF_STEPS.getFirst());
                    backoffRepository.save(created);
                    return true;
                }
                if (row.step >= BACKOFF_STEPS.size() || now.isBefore(row.nextEligibleAt)) {
                    return false;
                }
                int nextStep = row.step + 1;
                row.step = nextStep;
                if (nextStep >= BACKOFF_STEPS.size()) {
                    backoffRepository.save(row);
                    return false;
                }
                row.nextEligibleAt = now.plus(BACKOFF_STEPS.get(nextStep));
                backoffRepository.save(row);
                log.warn(
                        "unmapped code backoff step increased supplier={} property={} room={} step={}",
                        supplier.value(),
                        propertyCode,
                        roomTypeCode,
                        nextStep);
                return true;
            }));
            if (shouldSync) {
                requestManual();
            }
        }

        void runSyncCatching() {
            for (SupplierCatalogPort port : catalogPorts) {
                try {
                    syncSupplier(port);
                } catch (RuntimeException ex) {
                    log.warn("property list sync failed supplier={}", port.supplierId().value(), ex);
                }
            }
        }

        @PreDestroy
        void shutdown() {
            syncExecutor.shutdownNow();
        }

        private void tryStart() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            syncExecutor.execute(this::runLoop);
        }

        private void runLoop() {
            try {
                do {
                    runAgain.set(false);
                    runSyncCatching();
                } while (runAgain.get());
            } finally {
                running.set(false);
                if (runAgain.get()) {
                    tryStart();
                }
            }
        }

        private void restoreSnapshotFromStore() {
            boolean anyReady = readinessRepository.findAll().stream().anyMatch(row -> row.ready);
            if (!anyReady) {
                return;
            }
            snapshotHolder.replace(loadReadySnapshot());
        }

        private void syncSupplier(SupplierCatalogPort port) {
            SupplierCatalog catalog;
            try {
                catalog = port.fetchCatalog().block();
            } catch (RuntimeException ex) {
                log.warn("property list catalog fetch failed supplier={}", port.supplierId().value(), ex);
                return;
            }
            if (catalog == null) {
                log.warn("property list catalog missing supplier={}", port.supplierId().value());
                return;
            }
            int incoming = catalog.properties().size();
            long oldActive = propertyRepository.findBySupplier(port.supplierId().value()).stream()
                    .filter(property -> property.active)
                    .count();
            if (incoming == 0) {
                log.warn("empty property list rejected supplier={}", port.supplierId().value());
                return;
            }
            if (oldActive > 0
                    && (oldActive - incoming) / (double) oldActive >= properties.shrinkRejectRatio()) {
                log.warn(
                        "property list shrink rejected supplier={} previousActive={} incoming={}",
                        port.supplierId().value(),
                        oldActive,
                        incoming);
                return;
            }
            transactionTemplate.executeWithoutResult(status -> applyAccepted(catalog));
        }

        private void applyAccepted(SupplierCatalog catalog) {
            upsertService.apply(catalog);
            String supplier = catalog.supplier().value();
            SupplierReadinessEntity readiness = readinessRepository
                    .findById(supplier)
                    .orElseGet(() -> {
                        SupplierReadinessEntity created = new SupplierReadinessEntity();
                        created.supplier = supplier;
                        return created;
                    });
            readiness.ready = true;
            readiness.lastSuccessAt = Instant.now(clock);
            readinessRepository.save(readiness);
            for (CatalogProperty property : catalog.properties()) {
                for (CatalogRoomType roomType : property.roomTypes()) {
                    backoffRepository.deleteBySupplierAndSupplierPropertyCodeAndSupplierRoomTypeCode(
                            supplier, property.supplierPropertyCode(), roomType.supplierRoomTypeCode());
                }
            }
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

    @RestController
    public static class MappingSyncController {

        private final PropertyListSynchronizer synchronizer;

        MappingSyncController(PropertyListSynchronizer synchronizer) {
            this.synchronizer = synchronizer;
        }

        @PostMapping("/internal/mapping/sync")
        public ResponseEntity<Void> sync() {
            synchronizer.requestManual();
            return ResponseEntity.accepted().build();
        }
    }
}

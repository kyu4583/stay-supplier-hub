package stay.supplierhub.mapping;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import stay.supplierhub.mapping.MappingStore.MappingSnapshotHolder;
import stay.supplierhub.mapping.MappingStore.MappingUpsertService;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@ConfigurationProperties(prefix = "stay.mapping")
record MappingProperties(boolean syncOnStartup, Duration syncInterval, double shrinkRejectRatio) {}

public final class MappingSync {

    private MappingSync() {}

    @Component
    public static class PropertyListSynchronizer {

        PropertyListSynchronizer(
                List<SupplierCatalogPort> catalogPorts,
                MappingUpsertService upsertService,
                MappingSnapshotHolder snapshotHolder,
                MappingProperties properties,
                PropertyRepository propertyRepository,
                SupplierReadinessRepository readinessRepository,
                UnmappedCodeBackoffRepository backoffRepository,
                Clock clock,
                PlatformTransactionManager transactionManager) {}

        public void requestForUnmapped(SupplierId supplier, String propertyCode, String roomTypeCode) {}

        public void requestManual() {}

        public void onSchedule() {}

        public void onApplicationReady() {}

        void runSyncCatching() {}
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

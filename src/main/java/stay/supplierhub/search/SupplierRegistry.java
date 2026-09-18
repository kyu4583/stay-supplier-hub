package stay.supplierhub.search;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;
import stay.supplierhub.search.SupplierContracts.SupplierId;

@Component
public class SupplierRegistry {

    public SupplierRegistry(
            List<SupplierCatalogPort> catalogPorts,
            List<SupplierAvailabilityPort> availabilityPorts,
            Environment environment) {
        validate(catalogPorts.stream().map(SupplierCatalogPort::supplierId).toList(), environment);
        validate(availabilityPorts.stream().map(SupplierAvailabilityPort::supplierId).toList(), environment);
    }

    private static void validate(List<SupplierId> ids, Environment environment) {
        Set<String> seen = new HashSet<>();
        for (SupplierId id : ids) {
            String value = id.value();
            if (!seen.add(value)) {
                throw new IllegalStateException("duplicate supplier id: " + value);
            }
            String property = "stay.supplier." + value.toLowerCase(Locale.ROOT) + ".base-url";
            if (!StringUtils.hasText(environment.getProperty(property))) {
                throw new IllegalStateException("supplier id has no config: " + value);
            }
        }
    }
}

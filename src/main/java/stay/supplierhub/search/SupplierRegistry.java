package stay.supplierhub.search;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import stay.supplierhub.search.SupplierContracts.SupplierAvailabilityPort;
import stay.supplierhub.search.SupplierContracts.SupplierCatalogPort;

@Component
public class SupplierRegistry {

    public SupplierRegistry(
            List<SupplierCatalogPort> catalogPorts,
            List<SupplierAvailabilityPort> availabilityPorts,
            Environment environment) {}
}

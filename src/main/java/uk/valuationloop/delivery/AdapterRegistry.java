package uk.valuationloop.delivery;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Finds the adapter named in a DeliverLead payload. Spring passes in every bean that implements CrmAdapter
 * (constructor injection of a List), so adding an adapter is adding one @Component class, nothing else.
 */
@Component
public class AdapterRegistry {

    private final Map<String, CrmAdapter> byName;

    public AdapterRegistry(List<CrmAdapter> adapters) {
        // toMap throws on a duplicate name, which is what we want: two adapters called "ok" is a wiring bug
        this.byName = adapters.stream().collect(Collectors.toUnmodifiableMap(CrmAdapter::name, Function.identity()));
    }

    public CrmAdapter forName(String name) {
        CrmAdapter adapter = byName.get(name);
        if (adapter == null) {
            throw new UnknownAdapter(name);
        }
        return adapter;
    }

    /** No such adapter: retrying will not create one, so this goes straight to the dead letter topic. */
    public static class UnknownAdapter extends RuntimeException {
        public UnknownAdapter(String name) {
            super("no CRM adapter named '" + name + "'");
        }
    }
}

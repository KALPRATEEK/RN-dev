import java.util.*;
import java.util.concurrent.*;

public class RoutingTableManager {
    private final Map<String, RoutingEntry> table = new ConcurrentHashMap<>();

    public void updateEntry(RoutingEntry entry) {
        String key = entry.destIP.getHostAddress() + ":" + entry.destPort;
        table.put(key, entry);
    }

    public Collection<RoutingEntry> getEntries() {
        return table.values();
    }

    public void removeEntry(String key) {
        table.remove(key);
    }
}

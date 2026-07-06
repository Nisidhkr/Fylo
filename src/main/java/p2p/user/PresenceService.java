package p2p.user;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online presence for logged-in users (Username Share needs to know whether
 * the receiver can accept a live transfer). A user is online while any of
 * their clients has touched presence within the TTL — clients refresh it by
 * polling notifications or calling any authenticated endpoint.
 */
public final class PresenceService {

    private static final long ONLINE_TTL_MS = 45_000;

    private final ConcurrentHashMap<String, Long> lastSeenByUserId = new ConcurrentHashMap<>();

    /** Called by the API layer on every authenticated request. */
    public void touch(String userId) {
        lastSeenByUserId.put(userId, System.currentTimeMillis());
    }

    public boolean isOnline(String userId) {
        Long lastSeen = lastSeenByUserId.get(userId);
        return lastSeen != null && System.currentTimeMillis() - lastSeen < ONLINE_TTL_MS;
    }

    public long lastSeenEpochMs(String userId) {
        return lastSeenByUserId.getOrDefault(userId, 0L);
    }

    public Set<String> onlineUserIds() {
        long cutoff = System.currentTimeMillis() - ONLINE_TTL_MS;
        return lastSeenByUserId.entrySet().stream()
                .filter(e -> e.getValue() >= cutoff)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}

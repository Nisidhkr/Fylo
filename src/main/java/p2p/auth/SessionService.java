package p2p.auth;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh-token session registry with rotation: each refresh token is valid
 * for exactly one refresh; using it invalidates it and issues a successor.
 * A stolen-then-replayed token therefore dies on second use.
 *
 * <p>Keyed by the JWT {@code jti} claim, so only the id set — not the tokens
 * themselves — is held server-side. In-memory for Part 1 (single node);
 * becomes a Redis/PostgreSQL set in Part 2 behind these same three methods.
 */
public final class SessionService {

    private record Session(String userId, long expiresAtEpochSec) {
    }

    private final ConcurrentHashMap<String, Session> activeByTokenId = new ConcurrentHashMap<>();

    public void register(String tokenId, String userId, long expiresAtEpochSec) {
        activeByTokenId.put(tokenId, new Session(userId, expiresAtEpochSec));
        evictExpired();
    }

    /** Atomically consumes the session; false if unknown, expired, or replayed. */
    public boolean consume(String tokenId, String userId) {
        Session session = activeByTokenId.remove(tokenId);
        return session != null
                && session.userId().equals(userId)
                && session.expiresAtEpochSec() >= System.currentTimeMillis() / 1000;
    }

    /** Logout-everywhere support. */
    public void revokeAllFor(String userId) {
        activeByTokenId.entrySet().removeIf(e -> e.getValue().userId().equals(userId));
    }

    private void evictExpired() {
        long now = System.currentTimeMillis() / 1000;
        activeByTokenId.entrySet().removeIf(e -> e.getValue().expiresAtEpochSec() < now);
    }
}

package p2p.user;

/**
 * A registered account (modes 3 and 4 only; Direct and Nearby Share never
 * require one). The password hash format is owned by
 * {@link p2p.auth.PasswordHasher}.
 */
public record User(
        String userId,
        String username,
        String displayName,
        String passwordHash,
        long createdAtEpochMs) {

    /** Public projection — never leaks the password hash. */
    public record Profile(String userId, String username, String displayName, boolean online) {
    }

    public Profile profile(boolean online) {
        return new Profile(userId, username, displayName, online);
    }
}

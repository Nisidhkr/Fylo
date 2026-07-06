package p2p.user;

import java.util.List;
import java.util.Optional;

/**
 * The ONE persistence port for accounts. Part 1 ships a JSON-file
 * implementation ({@link JsonUserRepository}); Part 2 swaps in PostgreSQL
 * behind this same interface — no service-layer change.
 */
public interface UserRepository {

    Optional<User> findById(String userId);

    /** Case-insensitive exact match. */
    Optional<User> findByUsername(String username);

    /** Case-insensitive prefix search for @mention-style lookup. */
    List<User> searchByUsernamePrefix(String prefix, int limit);

    /** Inserts or updates by userId. */
    void save(User user);
}

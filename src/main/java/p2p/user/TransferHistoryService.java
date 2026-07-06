package p2p.user;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Account-scoped transfer history (mode 3/4 requirement). Device-local queue
 * history stays in {@code TransferManager}; this records the durable,
 * user-visible ledger. In-memory ring for Part 1; becomes the
 * {@code transfer_history} table in Part 2.
 */
public final class TransferHistoryService {

    public enum Mode { DIRECT, NEARBY, USERNAME, LINK }

    public record Entry(String id, String userId, String direction, Mode mode, String fileName,
                        long sizeBytes, String counterparty, String status, long atEpochMs) {
    }

    private static final int MAX_ENTRIES = 5_000;

    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

    public void record(String userId, String direction, Mode mode, String fileName,
                       long sizeBytes, String counterparty, String status) {
        entries.addLast(new Entry(UUID.randomUUID().toString(), userId, direction, mode,
                fileName, sizeBytes, counterparty, status, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
    }

    public List<Entry> listFor(String userId, int limit) {
        return entries.stream()
                .filter(e -> e.userId().equals(userId))
                .sorted(Comparator.comparingLong(Entry::atEpochMs).reversed())
                .limit(limit)
                .toList();
    }
}

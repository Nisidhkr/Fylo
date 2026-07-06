package p2p.engine;

import java.util.Optional;

/**
 * The one place that understands share codes. A code is what Direct Share
 * users exchange ("invite code") and what QR payloads embed; today it is
 * {@code <port>-<token>} pointing at a live {@code FileSender}.
 *
 * <p>Keeping encode/decode here means the format can evolve (e.g. to
 * server-brokered rendezvous ids for cross-network transfers) without any
 * mode service changing.
 */
public final class ShareCodes {

    /** A parsed share code. */
    public record Code(int port, String token) {

        public String encoded() {
            return port + "-" + token;
        }

        /** Payload for QR rendering on any client. */
        public String qrPayload() {
            return "fylo://direct/" + encoded();
        }
    }

    private ShareCodes() {
    }

    public static Code of(int port, String token) {
        return new Code(port, token);
    }

    public static Optional<Code> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.startsWith("fylo://direct/")) {
            value = value.substring("fylo://direct/".length());
        }
        int dash = value.indexOf('-');
        if (dash <= 0 || dash == value.length() - 1) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(value.substring(0, dash));
            String token = value.substring(dash + 1);
            if (port < 1 || port > 65535 || token.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Code(port, token));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

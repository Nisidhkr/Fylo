package p2p.share;

import p2p.engine.ShareCodes;
import p2p.engine.TransferEngine;
import p2p.engine.TransferSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * MODE 1 — Direct Share (guest, live). Sender uploads a file to their own
 * node, gets a share code / QR payload; the receiver connects with the code
 * and pulls straight from the sender through the one engine. Nothing is
 * persisted; when the sender's share closes, the code is dead.
 */
public final class DirectShareService {

    /** Everything the UI needs to show after offering a file. */
    public record DirectShare(int port, String token, String code, String qrPayload,
                              String fileName, long size) {
    }

    private final TransferEngine engine;

    public DirectShareService(TransferEngine engine) {
        this.engine = engine;
    }

    /** Offers an already-uploaded (or local) file and mints its share code. */
    public DirectShare create(Path file, String relativePath) throws IOException {
        String displayName = p2p.utils.MultipartUploads.stripUploadPrefix(
                file.getFileName().toString());
        TransferEngine.Share share = engine.offer(new TransferSource.LiveFile(
                file, displayName, Files.size(file), relativePath));
        return toView(share);
    }

    /** Resolves a share code typed or scanned by a receiver. */
    public Optional<ShareCodes.Code> resolve(String rawCode) {
        return ShareCodes.parse(rawCode);
    }

    public void stop(int port) {
        engine.stopShare(port);
    }

    public Map<String, Object> asJson(DirectShare share) {
        return Map.of(
                "port", share.port(),
                "token", share.token(),
                "code", share.code(),
                "qr", share.qrPayload(),
                "name", share.fileName(),
                "size", share.size());
    }

    private static DirectShare toView(TransferEngine.Share share) {
        return new DirectShare(share.port(), share.token(), share.code().encoded(),
                share.code().qrPayload(), share.fileName(), share.size());
    }
}

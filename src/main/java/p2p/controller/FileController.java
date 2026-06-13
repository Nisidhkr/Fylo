package p2p.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.fileupload.MultipartStream;
import p2p.device.DeviceDiscoveryService;
import p2p.device.DeviceIdentity;
import p2p.device.DevicePresenceManager;
import p2p.device.DeviceRegistry;
import p2p.device.HeartbeatService;
import p2p.lan.ControlPlaneClient;
import p2p.lan.IncomingOffer;
import p2p.lan.LanMessages;
import p2p.lan.LanShareService;
import p2p.lan.OfferManager;
import p2p.protocol.PeerLinkProtocol;
import p2p.protocol.TransferException;
import p2p.protocol.TransferManifest;
import p2p.service.FileSharer;
import p2p.transfer.FileReceiver;
import p2p.transfer.PeerClient;
import p2p.transfer.TransferConfig;
import p2p.transfer.TransferManager;
import p2p.util.Hashing;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP gateway in front of the transfer engine. Serves two transfer modes
 * with one engine underneath:
 *
 * <p><b>Internet mode (unchanged API):</b>
 * <ul>
 *   <li>{@code POST /upload} — multipart upload, streamed to disk; responds
 *       with the share port and access token (the invite code).</li>
 *   <li>{@code GET /download/{port}?token=...} — streams the file from the
 *       sharing peer straight through to the browser, with Range support.</li>
 * </ul>
 *
 * <p><b>LAN mode (new):</b>
 * <ul>
 *   <li>{@code GET /lan/ping} — liveness + identity (heartbeats).</li>
 *   <li>{@code GET /lan/devices} — discovered/recent devices for the UI.</li>
 *   <li>{@code POST /lan/send} — offer existing shares to a nearby device.</li>
 *   <li>{@code POST /lan/offer} — device-to-device: incoming offer.</li>
 *   <li>{@code GET /lan/offers} / {@code POST /lan/offers/{id}} — approval UI.</li>
 *   <li>{@code POST /lan/offer-result} — device-to-device: offer outcome.</li>
 *   <li>{@code GET /transfers} / {@code POST /transfers/{id}} — queue,
 *       progress, pause/resume/cancel.</li>
 * </ul>
 */
public class FileController {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename=\"([^\"]*)\"");
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    /** Request bodies of the JSON endpoints. */
    public record SendRequest(String deviceId, List<Integer> ports) {
    }

    public record OfferActionRequest(String action, Boolean trust) {
    }

    public record TransferActionRequest(String action) {
    }

    /** Manual device add for networks where mDNS multicast is unavailable. */
    public record ConnectRequest(String host, Integer port) {
    }

    public record OfferView(String offerId, String fromDeviceId, String fromName, String fromOs,
                            int fileCount, long totalBytes, List<String> fileNames,
                            long createdAtEpochMs) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final Path uploadDir;
    private final ExecutorService executor;

    private final DeviceIdentity identity;
    private final DeviceRegistry deviceRegistry;
    private final DeviceDiscoveryService discoveryService;
    private final DevicePresenceManager presenceManager;
    private final TransferManager transferManager;
    private final OfferManager offerManager;
    private final LanShareService lanShareService;
    private final ControlPlaneClient controlPlane;

    public FileController(int port) throws IOException {
        this(port,
                Path.of(System.getProperty("peerlink.data.dir",
                        System.getProperty("user.home") + "/.peerlink")),
                Path.of(System.getProperty("peerlink.downloads.dir",
                        System.getProperty("user.home") + "/Downloads/PeerLink")));
    }

    public FileController(int port, Path dataDir, Path downloadsDir) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = Path.of(System.getProperty("java.io.tmpdir"), "peerlink-uploads");
        Files.createDirectories(uploadDir);
        Files.createDirectories(downloadsDir);

        int boundPort = server.getAddress().getPort();
        this.identity = DeviceIdentity.loadOrCreate(dataDir);
        this.deviceRegistry = new DeviceRegistry(dataDir);
        this.transferManager = new TransferManager(3);
        this.controlPlane = new ControlPlaneClient();
        this.offerManager = new OfferManager(deviceRegistry, transferManager, controlPlane, downloadsDir);
        this.lanShareService = new LanShareService(identity, deviceRegistry, fileSharer,
                transferManager, controlPlane, boundPort);
        this.discoveryService = new DeviceDiscoveryService(identity, deviceRegistry, boundPort);
        this.presenceManager = new DevicePresenceManager(deviceRegistry, new HeartbeatService(), 10);

        // Virtual threads: each in-flight upload/download blocks cheaply
        // instead of pinning one of N pool threads for the whole transfer.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/lan", new LanHandler());
        server.createContext("/transfers", new TransfersHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
        presenceManager.start();
        // mDNS startup can take a few seconds and may fail on networks without
        // multicast; never block or break the web flow because of it.
        Thread.ofVirtual().name("mdns-startup").start(() -> {
            try {
                discoveryService.start();
                System.out.println("LAN discovery active as '" + identity.name() + "'");
            } catch (Exception e) {
                System.err.println("LAN discovery unavailable (" + e.getMessage()
                        + "); internet sharing unaffected");
            }
        });
    }

    public void stop() {
        server.stop(0);
        try {
            discoveryService.close();
        } catch (IOException ignored) {
        }
        presenceManager.close();
        transferManager.close();
        fileSharer.close();
        executor.shutdown();
        System.out.println("API server stopped");
    }

    /** Exposed for integration tests and future UI needs. */
    public DeviceRegistry deviceRegistry() {
        return deviceRegistry;
    }

    public DeviceIdentity identity() {
        return identity;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void addCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization,Range,X-Relative-Path");
    }

    private static boolean handlePreflight(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body instanceof String s
                ? s.getBytes(StandardCharsets.UTF_8)
                : json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            sendText(exchange, 404, "Not Found");
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                sendText(exchange, 400, "Bad Request: Content-Type must be multipart/form-data");
                return;
            }
            String boundary = extractBoundary(contentType);
            if (boundary == null) {
                sendText(exchange, 400, "Bad Request: missing multipart boundary");
                return;
            }

            Path savedFile = null;
            try {
                savedFile = streamUploadToDisk(exchange.getRequestBody(), boundary);
                if (savedFile == null) {
                    sendText(exchange, 400, "Bad Request: no file field in request");
                    return;
                }

                String relativePath = exchange.getRequestHeaders().getFirst("X-Relative-Path");
                FileSharer.Offer offer = fileSharer.offer(savedFile,
                        relativePath != null && !relativePath.isBlank() ? relativePath : null);
                FileSharer.ShareInfo info = fileSharer.shareInfo(offer.port()).orElseThrow();
                sendJson(exchange, 200, Map.of(
                        "port", offer.port(),
                        "token", offer.token(),
                        "name", info.fileName(),
                        "size", info.size()));
            } catch (Exception e) {
                System.err.println("Error processing upload: " + e);
                if (savedFile != null) {
                    Files.deleteIfExists(savedFile);
                }
                sendText(exchange, 500, "Server error while storing upload");
            }
        }

        /**
         * Streams the first file part directly to disk through a 64 KB buffer:
         * memory use is constant regardless of file size.
         */
        private Path streamUploadToDisk(InputStream body, String boundary) throws IOException {
            MultipartStream multipart = new MultipartStream(
                    body, boundary.getBytes(StandardCharsets.ISO_8859_1), COPY_BUFFER_BYTES, null);
            Path savedFile = null;
            boolean hasNext = multipart.skipPreamble();
            while (hasNext) {
                String partHeaders = multipart.readHeaders();
                Matcher matcher = FILENAME_PATTERN.matcher(partHeaders);
                if (savedFile == null && matcher.find()) {
                    String safeName = FileReceiver.sanitizeFilename(matcher.group(1));
                    Path destination = uploadDir.resolve(UUID.randomUUID() + "_" + safeName);
                    try (OutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(destination), COPY_BUFFER_BYTES)) {
                        multipart.readBodyData(out);
                    }
                    savedFile = destination;
                } else {
                    multipart.discardBodyData();
                }
                hasNext = multipart.readBoundary();
            }
            return savedFile;
        }

        private String extractBoundary(String contentType) {
            int index = contentType.indexOf("boundary=");
            if (index == -1) {
                return null;
            }
            String boundary = contentType.substring(index + "boundary=".length());
            int semicolon = boundary.indexOf(';');
            if (semicolon != -1) {
                boundary = boundary.substring(0, semicolon);
            }
            boundary = boundary.trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
            return boundary.isEmpty() ? null : boundary;
        }
    }

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            int port;
            try {
                port = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                sendText(exchange, 400, "Bad Request: invalid port");
                return;
            }
            String token = extractToken(exchange);
            if (token == null) {
                sendText(exchange, 401,
                        "Missing transfer token (use ?token=... or Authorization: Bearer ...)");
                return;
            }
            long offset = parseRangeOffset(exchange.getRequestHeaders().getFirst("Range"));

            try (PeerClient client = PeerClient.connect(
                    new InetSocketAddress("localhost", port), token, TransferConfig.lan())) {
                TransferManifest manifest = client.manifest();
                if (offset < 0 || offset > manifest.fileSize()) {
                    sendText(exchange, 416, "Requested range not satisfiable");
                    return;
                }
                long length = client.requestRange(offset, -1);

                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "application/octet-stream");
                headers.set("Content-Disposition", "attachment; filename=\""
                        + FileReceiver.sanitizeFilename(manifest.filename()) + "\"");
                headers.set("Accept-Ranges", "bytes");
                if (manifest.hasHash()) {
                    headers.set("X-Content-SHA256", Hashing.toHex(manifest.sha256()));
                }
                if (offset > 0) {
                    headers.set("Content-Range", "bytes " + offset + "-"
                            + (manifest.fileSize() - 1) + "/" + manifest.fileSize());
                    exchange.sendResponseHeaders(206, length);
                } else {
                    exchange.sendResponseHeaders(200, length);
                }

                // Stream peer -> browser directly; no temp file, constant memory.
                try (OutputStream out = exchange.getResponseBody()) {
                    copyExactly(Channels.newInputStream(client.channel()), out, length);
                }
                client.readComplete();
            } catch (TransferException e) {
                int status = e.code() == PeerLinkProtocol.ERR_AUTH_FAILED ? 403 : 502;
                sendText(exchange, status, "Peer error: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Download via port " + port + " failed: " + e.getMessage());
                // Headers may already be sent; only report if the response is still open.
                if (exchange.getResponseCode() == -1) {
                    sendText(exchange, 502, "Could not reach peer on port " + port);
                }
            }
        }

        private String extractToken(HttpExchange exchange) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        return param.substring("token=".length());
                    }
                }
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth.substring("Bearer ".length()).trim();
            }
            return null;
        }

        private long parseRangeOffset(String rangeHeader) {
            if (rangeHeader == null) {
                return 0;
            }
            Matcher matcher = RANGE_PATTERN.matcher(rangeHeader);
            return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0;
        }

        private void copyExactly(InputStream in, OutputStream out, long length) throws IOException {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Peer closed connection mid-transfer");
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /** Routes /lan/* — device discovery, offers, and the M2M control plane. */
    private class LanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            try {
                switch (method + " " + route(path)) {
                    case "GET /lan/ping" -> sendJson(exchange, 200, Map.of(
                            "deviceId", identity.deviceId(), "name", identity.name()));
                    case "GET /lan/devices" -> sendJson(exchange, 200, Map.of(
                            "self", Map.of("deviceId", identity.deviceId(), "name", identity.name(),
                                    "os", identity.os(), "type", identity.type()),
                            "devices", deviceRegistry.snapshots()));
                    case "POST /lan/send" -> handleSend(exchange);
                    case "POST /lan/hello" -> handleHello(exchange);
                    case "POST /lan/connect" -> handleConnect(exchange);
                    case "POST /lan/offer" -> handleIncomingOffer(exchange);
                    case "GET /lan/offers" -> sendJson(exchange, 200,
                            offerManager.pending().stream().map(LanHandler::toView).toList());
                    case "POST /lan/offers/{id}" -> handleOfferAction(exchange, lastSegment(path));
                    case "POST /lan/offer-result" -> {
                        lanShareService.onOfferResult(
                                json.readValue(exchange.getRequestBody(), LanMessages.OfferResult.class));
                        sendJson(exchange, 200, Map.of("ok", true));
                    }
                    default -> sendText(exchange, 404, "Not Found");
                }
            } catch (IOException e) {
                sendText(exchange, 400, "Bad Request: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("LAN endpoint error: " + e);
                sendText(exchange, 500, "Internal error");
            }
        }

        private void handleSend(HttpExchange exchange) throws IOException {
            SendRequest request = json.readValue(exchange.getRequestBody(), SendRequest.class);
            if (request.deviceId() == null || request.ports() == null || request.ports().isEmpty()) {
                sendText(exchange, 400, "deviceId and ports are required");
                return;
            }
            try {
                String offerId = lanShareService.send(request.deviceId(), request.ports());
                sendJson(exchange, 200, Map.of("offerId", offerId));
            } catch (IOException e) {
                sendText(exchange, 502, e.getMessage());
            }
        }

        /** A peer introduces itself; register it and answer with our identity. */
        private void handleHello(HttpExchange exchange) throws IOException {
            LanMessages.Hello hello = json.readValue(exchange.getRequestBody(), LanMessages.Hello.class);
            if (hello.deviceId() == null || hello.deviceId().isBlank()) {
                sendText(exchange, 400, "deviceId is required");
                return;
            }
            String peerHost = exchange.getRemoteAddress().getAddress().getHostAddress();
            deviceRegistry.upsertOnline(hello.deviceId(), hello.name(), hello.os(),
                    hello.deviceType(), peerHost, hello.apiPort());
            sendJson(exchange, 200, new LanMessages.Hello(identity.deviceId(), identity.name(),
                    identity.os(), identity.type(), port()));
        }

        /** UI asked to add a device by address (multicast-free fallback). */
        private void handleConnect(HttpExchange exchange) throws IOException {
            ConnectRequest request = json.readValue(exchange.getRequestBody(), ConnectRequest.class);
            if (request.host() == null || request.host().isBlank()) {
                sendText(exchange, 400, "host is required");
                return;
            }
            int peerPort = request.port() == null || request.port() <= 0 ? 9090 : request.port();
            try {
                LanMessages.Hello peer = controlPlane.exchangeHello(request.host(), peerPort,
                        new LanMessages.Hello(identity.deviceId(), identity.name(),
                                identity.os(), identity.type(), port()));
                if (peer.deviceId() == null || peer.deviceId().equals(identity.deviceId())) {
                    sendText(exchange, 400, "That address is this device");
                    return;
                }
                deviceRegistry.upsertOnline(peer.deviceId(), peer.name(), peer.os(),
                        peer.deviceType(), request.host(), peerPort);
                sendJson(exchange, 200, Map.of("deviceId", peer.deviceId(), "name", peer.name()));
            } catch (IOException e) {
                sendText(exchange, 502, "Could not reach " + request.host() + ":" + peerPort
                        + " — is PeerLink running there? (" + e.getMessage() + ")");
            }
        }

        private void handleIncomingOffer(HttpExchange exchange) throws IOException {
            LanMessages.OfferRequest request =
                    json.readValue(exchange.getRequestBody(), LanMessages.OfferRequest.class);
            if (request.offerId() == null || request.files() == null || request.files().isEmpty()) {
                sendText(exchange, 400, "offerId and files are required");
                return;
            }
            String senderHost = exchange.getRemoteAddress().getAddress().getHostAddress();
            IncomingOffer offer = offerManager.register(request, senderHost);
            sendJson(exchange, 200, Map.of("offerId", offer.offerId(), "state", offer.state().name()));
        }

        private void handleOfferAction(HttpExchange exchange, String offerId) throws IOException {
            OfferActionRequest action =
                    json.readValue(exchange.getRequestBody(), OfferActionRequest.class);
            boolean ok = switch (action.action() == null ? "" : action.action()) {
                case "accept" -> offerManager.accept(offerId, Boolean.TRUE.equals(action.trust()));
                case "reject" -> offerManager.reject(offerId);
                default -> false;
            };
            if (ok) {
                sendJson(exchange, 200, Map.of("ok", true));
            } else {
                sendText(exchange, 404, "Unknown offer or already handled");
            }
        }

        private static OfferView toView(IncomingOffer offer) {
            return new OfferView(offer.offerId(), offer.senderDeviceId(), offer.senderName(),
                    offer.senderOs(), offer.files().size(), offer.totalBytes(),
                    offer.files().stream().map(LanMessages.OfferFile::name).toList(),
                    offer.createdAtEpochMs());
        }

        /** Collapses /lan/offers/<uuid> to /lan/offers/{id} for routing. */
        private static String route(String path) {
            if (path.startsWith("/lan/offers/")) {
                return "/lan/offers/{id}";
            }
            return path.endsWith("/") && path.length() > 1
                    ? path.substring(0, path.length() - 1) : path;
        }

        private static String lastSegment(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** Routes /transfers — queue snapshots and pause/resume/cancel. */
    private class TransfersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            try {
                if (method.equals("GET")) {
                    sendJson(exchange, 200, transferManager.snapshots());
                    return;
                }
                if (method.equals("POST") && path.startsWith("/transfers/")) {
                    String id = path.substring("/transfers/".length());
                    TransferActionRequest action =
                            json.readValue(exchange.getRequestBody(), TransferActionRequest.class);
                    boolean ok = switch (action.action() == null ? "" : action.action()) {
                        case "pause" -> transferManager.pause(id);
                        case "resume" -> transferManager.resume(id);
                        case "cancel" -> transferManager.cancel(id);
                        default -> false;
                    };
                    if (ok) {
                        sendJson(exchange, 200, Map.of("ok", true));
                    } else {
                        sendText(exchange, 404, "Unknown transfer or action not applicable");
                    }
                    return;
                }
                sendText(exchange, 405, "Method Not Allowed");
            } catch (IOException e) {
                sendText(exchange, 400, "Bad Request: " + e.getMessage());
            }
        }
    }
}

package p2p.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.fileupload.MultipartStream;
import p2p.protocol.PeerLinkProtocol;
import p2p.protocol.TransferException;
import p2p.protocol.TransferManifest;
import p2p.service.FileSharer;
import p2p.transfer.FileReceiver;
import p2p.transfer.PeerClient;
import p2p.transfer.TransferConfig;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP gateway in front of the binary transfer protocol.
 *
 * <ul>
 *   <li>{@code POST /upload} — multipart upload, <b>streamed to disk</b>
 *       (constant memory regardless of file size). Responds with the share
 *       port and access token.</li>
 *   <li>{@code GET /download/{port}?token=...} — connects to the local peer
 *       sender and streams the file straight through to the HTTP response (no
 *       temp file, constant memory). Supports HTTP {@code Range} for browser
 *       resume.</li>
 * </ul>
 */
public class FileController {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("filename=\"([^\"]*)\"");
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("bytes=(\\d+)-(\\d*)");

    private final FileSharer fileSharer;
    private final HttpServer server;
    private final Path uploadDir;
    private final ExecutorService executor;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = Path.of(System.getProperty("java.io.tmpdir"), "peerlink-uploads");
        Files.createDirectories(uploadDir);

        // Virtual threads: each in-flight upload/download blocks cheaply
        // instead of pinning one of N pool threads for the whole transfer.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        fileSharer.close();
        executor.shutdown();
        System.out.println("API server stopped");
    }

    private static void addCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization,Range");
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            sendText(exchange, 404, "Not Found");
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCors(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
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

                FileSharer.Offer offer = fileSharer.offer(savedFile);
                sendJson(exchange, 200,
                        "{\"port\": " + offer.port() + ", \"token\": \"" + offer.token() + "\"}");
            } catch (Exception e) {
                System.err.println("Error processing upload: " + e);
                if (savedFile != null) {
                    Files.deleteIfExists(savedFile);
                }
                sendText(exchange, 500, "Server error while storing upload");
            }
        }

        /**
         * Streams the first file part directly to disk through a 64 KB buffer.
         * Unlike the previous ByteArrayOutputStream approach, memory use is
         * constant — a 40 GB upload needs ~64 KB, not 40 GB, of heap.
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
            addCors(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
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
                sendText(exchange, 401, "Missing transfer token (use ?token=... or Authorization: Bearer ...)");
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
                    headers.set("Content-Range",
                            "bytes " + offset + "-" + (manifest.fileSize() - 1) + "/" + manifest.fileSize());
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
}

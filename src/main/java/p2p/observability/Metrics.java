package p2p.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Dependency-free Prometheus metrics registry, exposed at {@code /metrics}
 * in text exposition format 0.0.4 (what Prometheus scrapes). Counters are
 * {@link LongAdder}s (uncontended under virtual-thread swarms); gauges are
 * sampled at scrape time via {@link LongSupplier} callbacks, so hot paths
 * never pay for observation.
 *
 * <p>Series naming: pass the full series including labels, e.g.
 * {@code counter("fylo_http_requests_total{route=\"api\",status=\"200\"}")}.
 * JVM gauges (heap, threads) are registered by the composition root.
 */
public final class Metrics {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void increment(String series) {
        add(series, 1);
    }

    public void add(String series, long delta) {
        counters.computeIfAbsent(series, s -> new LongAdder()).add(delta);
    }

    /** Registers a gauge sampled at scrape time. */
    public void gauge(String series, LongSupplier supplier) {
        gauges.put(series, supplier);
    }

    public String render() {
        StringBuilder out = new StringBuilder(1024);
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(e.getKey()).append(' ')
                        .append(e.getValue().sum()).append('\n'));
        gauges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(e.getKey()).append(' ')
                        .append(e.getValue().getAsLong()).append('\n'));
        return out.toString();
    }

    /** Handler for the /metrics scrape endpoint. */
    public HttpHandler handler() {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = render().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        };
    }

    /** Standard JVM gauges every deployment wants on a dashboard. */
    public void registerJvmGauges() {
        Runtime runtime = Runtime.getRuntime();
        gauge("fylo_jvm_heap_used_bytes", () -> runtime.totalMemory() - runtime.freeMemory());
        gauge("fylo_jvm_heap_max_bytes", runtime::maxMemory);
        gauge("fylo_jvm_available_processors", runtime::availableProcessors);
    }
}

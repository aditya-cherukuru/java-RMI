package arena.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counts RMI traffic in both directions and keeps a human-readable call log. */
final class Telemetry {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int LOG_LINES = 300;

    private final Map<String, LongAdder> totals = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTotals = new ConcurrentHashMap<>();
    private volatile Map<String, Long> rates = Map.of();
    private final Deque<String> log = new ArrayDeque<>();
    private volatile int frameBytes;

    /** An incoming client -> server remote call. */
    void call(String method) {
        totals.computeIfAbsent(method, k -> new LongAdder()).increment();
    }

    /** An outgoing server -> client callback. */
    void callback() {
        call("onFrame (callback)");
    }

    void log(String format, Object... args) {
        String line = LocalTime.now().format(TIME) + "  " + String.format(format, args);
        System.out.println(line);
        synchronized (log) {
            log.addLast(line);
            while (log.size() > LOG_LINES) log.removeFirst();
        }
    }

    List<String> logLines() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /** Called once per second to turn totals into per-second rates. */
    void sample() {
        Map<String, Long> next = new TreeMap<>();
        totals.forEach((k, v) -> {
            long now = v.sum();
            next.put(k, now - lastTotals.getOrDefault(k, now));
            lastTotals.put(k, now);
        });
        rates = next;
    }

    Map<String, Long> rates() {
        return rates;
    }

    long total(String method) {
        LongAdder a = totals.get(method);
        return a == null ? 0 : a.sum();
    }

    void frameBytes(int bytes) {
        frameBytes = bytes;
    }

    int frameBytes() {
        return frameBytes;
    }
}

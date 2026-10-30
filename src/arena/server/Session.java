package arena.server;

import arena.common.ArenaListener;
import arena.common.Frame;
import arena.common.GameEvent;
import arena.common.GameSnapshot;
import arena.common.Net;

import java.rmi.RemoteException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * One connected client and its outgoing channel.
 *
 * Each client gets its own push thread, so a slow or frozen laptop can never
 * stall the game loop or the other players. Snapshots are conflated (only the
 * newest one is worth sending), events are queued and never dropped silently.
 */
final class Session {
    private static final int MAX_QUEUED_EVENTS = 300;

    final long token;
    final int playerId;
    final String name;
    final String host;
    private final ArenaListener listener;
    private final Telemetry telemetry;

    private volatile boolean push;
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile double callbackMillis;
    private volatile int failures;
    final LongAdder framesPushed = new LongAdder();
    final LongAdder polls = new LongAdder();
    final LongAdder inputs = new LongAdder();

    private final Deque<GameEvent> outbox = new ArrayDeque<>();
    private final AtomicReference<GameSnapshot> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService pusher;

    Session(long token, int playerId, String name, String host, ArenaListener listener, Telemetry telemetry) {
        this.token = token;
        this.playerId = playerId;
        this.name = name;
        this.host = host;
        this.listener = listener;
        this.telemetry = telemetry;
        this.push = listener != null;
        this.pusher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "push-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    void touch() {
        lastSeen = System.currentTimeMillis();
    }

    long idleMillis() {
        return System.currentTimeMillis() - lastSeen;
    }

    boolean isPush() {
        return push;
    }

    double callbackMillis() {
        return callbackMillis;
    }

    int queuedEvents() {
        synchronized (outbox) {
            return outbox.size();
        }
    }

    void addEvents(List<GameEvent> events) {
        if (events.isEmpty()) return;
        synchronized (outbox) {
            outbox.addAll(events);
            while (outbox.size() > MAX_QUEUED_EVENTS) outbox.removeFirst();
        }
    }

    private List<GameEvent> drainEvents() {
        synchronized (outbox) {
            List<GameEvent> list = new ArrayList<>(outbox);
            outbox.clear();
            return list;
        }
    }

    private void requeue(List<GameEvent> events) {
        synchronized (outbox) {
            for (int i = events.size() - 1; i >= 0; i--) outbox.addFirst(events.get(i));
        }
    }

    /** Hand the newest snapshot to the push thread (replacing an unsent older one). */
    void offer(GameSnapshot snapshot) {
        if (!push) return;
        pending.set(snapshot);
        if (scheduled.compareAndSet(false, true)) {
            pusher.execute(this::drain);
        }
    }

    private void drain() {
        try {
            GameSnapshot s;
            while (push && (s = pending.getAndSet(null)) != null) {
                List<GameEvent> events = drainEvents();
                long t0 = System.nanoTime();
                try {
                    listener.onFrame(new Frame(s, events));   // the remote callback
                    double ms = (System.nanoTime() - t0) / 1e6;
                    callbackMillis = callbackMillis == 0 ? ms : callbackMillis * 0.9 + ms * 0.1;
                    failures = 0;
                    framesPushed.increment();
                    telemetry.callback();
                } catch (RemoteException e) {
                    requeue(events);
                    if (++failures >= 3) {
                        push = false;
                        telemetry.log("callback to %s @ %s failing (%s) - push stopped, client should fall back to polling",
                                name, host, Net.rootCause(e));
                    }
                }
            }
        } finally {
            scheduled.set(false);
            if (push && pending.get() != null && scheduled.compareAndSet(false, true)) {
                pusher.execute(this::drain);
            }
        }
    }

    /** Pull mode: the client asks, we answer with the latest state. */
    Frame poll(GameSnapshot latest) {
        if (push) {
            push = false;
            telemetry.log("%s switched to PULL mode (polling instead of callbacks)", name);
        }
        polls.increment();
        return new Frame(latest, drainEvents());
    }

    void close() {
        push = false;
        pusher.shutdownNow();
    }
}

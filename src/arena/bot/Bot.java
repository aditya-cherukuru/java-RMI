package arena.bot;

import arena.common.ArenaListener;
import arena.common.ArenaService;
import arena.common.Frame;
import arena.common.GameSnapshot;
import arena.common.JoinResult;
import arena.common.MapData;
import arena.common.Net;
import arena.common.Obstacle;
import arena.common.PickupView;
import arena.common.PlayerInput;
import arena.common.PlayerView;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Random;

import static arena.common.GameRules.REGISTRY_PORT;

/**
 * A computer player. It is a completely ordinary RMI client: it looks up the
 * registry, exports its own listener, joins, receives callbacks and sends
 * inputs - so bots on a third laptop generate real network traffic.
 *
 *   java -jar rmi-arena.jar bot <server-ip> [count]
 */
public final class Bot implements ArenaListener {

    private static final String[] NAMES = {
            "StubBot", "Skeleton", "Marshaller", "DGC-9000", "NullPointer", "ProxyBot", "Unmarshal", "RegistryRex",
    };

    private final Random rnd = new Random();
    private ArenaService arena;
    private long token;
    private int myId;
    private MapData map;
    private volatile GameSnapshot latest;
    private volatile long lastFrame = System.nanoTime();
    private volatile boolean running = true;

    // steering memory
    private double aimNoise;
    private double wanderTurn;
    private long wanderUntil;
    private double lastX, lastY;
    private long stuckSince;

    private Bot() {}

    /** Connect a new bot. Net.configureRmi must already have been called in this JVM. */
    public static Bot start(String host, String name) throws Exception {
        Bot bot = new Bot();
        bot.arena = (ArenaService) LocateRegistry.getRegistry(host, REGISTRY_PORT).lookup(ArenaService.NAME);
        ArenaListener stub = (ArenaListener) Net.exportCallback(bot);
        JoinResult r = bot.arena.join(name, stub);
        bot.token = r.token();
        bot.myId = r.playerId();
        bot.map = r.map();
        Thread t = new Thread(bot::thinkLoop, "bot-" + name);
        t.setDaemon(true);
        t.start();
        return bot;
    }

    public static String randomName(int i) {
        return NAMES[i % NAMES.length] + (i >= NAMES.length ? String.valueOf(i / NAMES.length + 1) : "");
    }

    public void stop() {
        running = false;
        try {
            arena.leave(token);
        } catch (RemoteException ignored) {
            // server may already be gone
        }
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (RemoteException ignored) {
            // not exported
        }
    }

    @Override
    public void onFrame(Frame frame) {
        latest = frame.snapshot();
        lastFrame = System.nanoTime();
    }

    private void thinkLoop() {
        while (running) {
            try {
                Thread.sleep(80);
                if (System.nanoTime() - lastFrame > 2_500_000_000L) {
                    onFrame(arena.poll(token));    // callbacks blocked: fall back to pulling
                }
                arena.sendInput(token, decide());
            } catch (InterruptedException e) {
                return;
            } catch (RemoteException e) {
                System.err.println("bot " + myId + ": " + Net.rootCause(e));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private PlayerInput decide() {
        GameSnapshot s = latest;
        if (s == null || s.phase() != GameSnapshot.Phase.PLAYING) return PlayerInput.NONE;
        PlayerView me = null;
        for (PlayerView p : s.players()) {
            if (p.id() == myId) me = p;
        }
        if (me == null || !me.alive()) return PlayerInput.NONE;

        long now = System.currentTimeMillis();
        if (rnd.nextInt(12) == 0) aimNoise = (rnd.nextDouble() - 0.5) * 0.22;

        // stuck against something? wander for a moment
        if (Math.hypot(me.x() - lastX, me.y() - lastY) > 4) {
            stuckSince = now;
        }
        lastX = me.x();
        lastY = me.y();
        if (now - stuckSince > 700 && now > wanderUntil) {
            wanderUntil = now + 500 + rnd.nextInt(500);
            wanderTurn = rnd.nextBoolean() ? 1 : -1;
        }
        if (now < wanderUntil) {
            return new PlayerInput(true, false, wanderTurn < 0, wanderTurn > 0, false, rnd.nextInt(20) == 0);
        }

        // low on HP and a repair kit is around: go get it
        double tx, ty;
        boolean hunting = true;
        PlayerView target = null;
        PickupView heal = null;
        if (me.hp() < 50) {
            for (PickupView k : s.pickups()) {
                if (k.type() == PickupView.REPAIR) heal = k;
            }
        }
        if (heal != null) {
            tx = heal.x();
            ty = heal.y();
            hunting = false;
        } else {
            double best = Double.MAX_VALUE;
            for (PlayerView p : s.players()) {
                if (p.id() == myId || !p.alive()) continue;
                double d = Math.hypot(p.x() - me.x(), p.y() - me.y()) + (p.shielded() ? 400 : 0);
                if (d < best) {
                    best = d;
                    target = p;
                }
            }
            if (target == null) return new PlayerInput(true, false, false, rnd.nextInt(3) == 0, false, false);
            tx = target.x();
            ty = target.y();
        }

        double dist = Math.hypot(tx - me.x(), ty - me.y());
        double want = Math.atan2(ty - me.y(), tx - me.x()) + (hunting ? aimNoise : 0);
        double diff = want - me.angle();
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;

        boolean left = diff < -0.07, right = diff > 0.07;
        boolean forward = !hunting || dist > 230 || !clearShot(me.x(), me.y(), tx, ty);
        boolean back = hunting && dist < 110;
        boolean fire = hunting && Math.abs(diff) < 0.16 && clearShot(me.x(), me.y(), tx, ty);
        boolean dash = hunting && dist < 260 && rnd.nextInt(25) == 0;
        return new PlayerInput(forward && Math.abs(diff) < 1.3, back, left, right, fire, dash);
    }

    private boolean clearShot(double x0, double y0, double x1, double y1) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int steps = (int) (len / 8);
        int[] hp = latest.crateHp();
        for (int i = 1; i < steps; i++) {
            double x = x0 + (x1 - x0) * i / steps, y = y0 + (y1 - y0) * i / steps;
            for (Obstacle o : map.obstacles()) {
                if ((o.mirror() || hp[o.id()] > 0)
                        && x >= o.x() && x <= o.x() + o.w() && y >= o.y() && y <= o.y() + o.h()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: bot <server-ip> [count]");
            System.exit(2);
        }
        String host = args[0];
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        Net.configureRmi(Net.localAddressTowards(host, REGISTRY_PORT));
        java.util.List<Bot> bots = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bot b = start(host, randomName(i));
            bots.add(b);
            System.out.println("bot " + randomName(i) + " joined as player #" + b.myId);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> bots.forEach(Bot::stop)));
        System.out.println("Bots running. Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}

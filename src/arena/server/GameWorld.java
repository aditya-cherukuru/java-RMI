package arena.server;

import arena.common.BeamView;
import arena.common.GameEvent;
import arena.common.GameEvent.Type;
import arena.common.GameSnapshot;
import arena.common.GameSnapshot.Phase;
import arena.common.MapData;
import arena.common.Obstacle;
import arena.common.PickupView;
import arena.common.PlayerInput;
import arena.common.PlayerView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static arena.common.GameRules.*;

/**
 * The authoritative simulation. Clients only send key states; everything
 * (movement, laser ray-tracing, damage, scores) is decided here.
 *
 * RMI calls arrive on many threads, the tick runs on one: every public
 * method is synchronized.
 */
final class GameWorld {

    static final double DT = 1.0 / TICK_RATE;

    private static final double TURN_SPEED = 3.8;
    private static final double MOVE_SPEED = 240;
    private static final double BACK_SPEED = 150;
    private static final double DASH_SPEED = 760;
    private static final double DASH_TIME = 0.16;
    private static final double DASH_COOLDOWN = 2.2;
    private static final double FIRE_COOLDOWN = 0.45;
    private static final double OVERCLOCK_FIRE_COOLDOWN = 0.15;
    private static final double OVERCLOCK_TIME = 6;
    private static final double BEAM_LIFE = 0.4;
    private static final double BEAM_RANGE = 1800;
    private static final int MAX_BOUNCES = 4;
    private static final int BEAM_DAMAGE = 34;
    private static final double BOUNCE_BONUS = 0.65;
    private static final double RESPAWN_TIME = 2.5;
    private static final double SPAWN_SHIELD = 1.5;
    private static final double CRATE_RESPAWN = 25;
    private static final double PICKUP_INTERVAL = 7;
    private static final double INTERMISSION = 8;
    private static final double EPS = 1e-3;

    private static final String[] KILL_LINES = {
            "%s zapped %s",
            "%s sent %s a RemoteException",
            "%2$s was garbage-collected by %1$s",
            "%s unexported %s",
            "%2$s timed out (thanks to %1$s)",
            "%s serialized %s into tiny bytes",
            "%s called %s.die() remotely",
    };

    static final class Player {
        final int id;
        final String name;
        final int color;
        double x, y, angle, vx, vy;
        int hp, kills, deaths, streak, multi;
        boolean alive;
        double respawnIn, shield, fireCd, dashCd, dashTime, overclock;
        double lastKillTime = -100;
        volatile PlayerInput input = PlayerInput.NONE;

        Player(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    private record Beam(long id, int ownerId, int color, float[] points, int bounces, double born) {}

    private record Pickup(int id, int type, double x, double y) {}

    record TickResult(GameSnapshot snapshot, List<GameEvent> events) {}

    private final MapData map;
    private final List<Obstacle> obstacles;
    private final int[] crateHp;
    private final double[] crateRespawn;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<Beam> beams = new ArrayList<>();
    private final List<Pickup> pickups = new ArrayList<>();
    private final List<GameEvent> pendingEvents = new ArrayList<>();
    private final Random rnd = new Random();
    private final int killLimit;
    private final double roundSeconds;

    private int nextPlayerId = 1;
    private int nextPickupId = 1;
    private long nextBeamId = 1;
    private long tick;
    private double time;
    private Phase phase = Phase.WAITING;
    private double phaseTime;
    private String winner = "";
    private double pickupTimer = 4;
    private volatile GameSnapshot latest;

    GameWorld(MapData map, int killLimit, double roundSeconds) {
        this.map = map;
        this.obstacles = map.obstacles();
        this.killLimit = killLimit;
        this.roundSeconds = roundSeconds;
        this.crateHp = new int[obstacles.size()];
        this.crateRespawn = new double[obstacles.size()];
        resetCrates();
        this.latest = buildSnapshot();
    }

    MapData map() {
        return map;
    }

    GameSnapshot latest() {
        return latest;
    }

    // ------------------------------------------------------------------ players

    synchronized Player addPlayer(String requestedName) {
        if (players.size() >= MAX_PLAYERS) {
            throw new IllegalStateException("Arena is full (" + MAX_PLAYERS + " players)");
        }
        String name = uniqueName(requestedName);
        Player p = new Player(nextPlayerId++, name, pickColor());
        players.put(p.id, p);
        spawn(p);
        if (phase == Phase.PLAYING) {
            p.alive = true;
        }
        event(Type.JOIN, name + " joined the arena", p.color, p.x, p.y, p.id);
        return p;
    }

    synchronized void removePlayer(int id, String reason) {
        Player p = players.remove(id);
        if (p != null) {
            event(Type.LEAVE, p.name + " " + reason, p.color, p.x, p.y, p.id);
        }
    }

    void setInput(int id, PlayerInput input) {
        Player p;
        synchronized (this) {
            p = players.get(id);
        }
        if (p != null) {
            p.input = input == null ? PlayerInput.NONE : input;
        }
    }

    synchronized void taunt(int id, int index) {
        Player p = players.get(id);
        if (p != null && index >= 0 && index < TAUNTS.length) {
            event(Type.TAUNT, p.name + ": " + TAUNTS[index], p.color, p.x, p.y, p.id);
        }
    }

    synchronized void restartRound() {
        if (!players.isEmpty()) {
            startRound();
        }
    }

    synchronized void systemMessage(String text) {
        event(Type.SYSTEM, text, 0xFFFFFF, 0, 0, 0);
    }

    private String uniqueName(String requested) {
        String base = requested == null || requested.isBlank() ? "anon" : requested.strip();
        if (base.length() > 16) base = base.substring(0, 16);
        String name = base;
        for (int i = 2; nameTaken(name); i++) {
            name = base + i;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        for (Player p : players.values()) {
            if (p.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private int pickColor() {
        outer:
        for (int c : PLAYER_COLORS) {
            for (Player p : players.values()) {
                if (p.color == c) continue outer;
            }
            return c;
        }
        return PLAYER_COLORS[rnd.nextInt(PLAYER_COLORS.length)];
    }

    // ------------------------------------------------------------------ tick

    synchronized TickResult tick() {
        tick++;
        time += DT;

        switch (phase) {
            case WAITING -> {
                if (!players.isEmpty()) startRound();
            }
            case PLAYING -> {
                if (players.isEmpty()) {
                    phase = Phase.WAITING;
                } else {
                    phaseTime -= DT;
                    if (phaseTime <= 0) endRound(leader());
                }
            }
            case INTERMISSION -> {
                phaseTime -= DT;
                if (phaseTime <= 0) {
                    if (players.isEmpty()) phase = Phase.WAITING;
                    else startRound();
                }
            }
        }

        for (Player p : players.values()) {
            updatePlayer(p);
        }
        separatePlayers();

        beams.removeIf(b -> time - b.born > BEAM_LIFE);

        for (int i = 0; i < obstacles.size(); i++) {
            if (!obstacles.get(i).mirror() && crateHp[i] <= 0) {
                crateRespawn[i] -= DT;
                if (crateRespawn[i] <= 0 && !anyPlayerInside(obstacles.get(i))) {
                    crateHp[i] = obstacles.get(i).maxHp();
                }
            }
        }

        updatePickups();

        GameSnapshot snapshot = buildSnapshot();
        latest = snapshot;
        List<GameEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return new TickResult(snapshot, events);
    }

    private void updatePlayer(Player p) {
        if (!p.alive) {
            p.respawnIn -= DT;
            if (p.respawnIn <= 0 && phase == Phase.PLAYING) {
                spawn(p);
                p.alive = true;
            }
            return;
        }

        p.shield = Math.max(0, p.shield - DT);
        p.fireCd = Math.max(0, p.fireCd - DT);
        p.dashCd = Math.max(0, p.dashCd - DT);
        p.dashTime = Math.max(0, p.dashTime - DT);
        p.overclock = Math.max(0, p.overclock - DT);

        PlayerInput in = p.input;
        int turn = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        p.angle = normalize(p.angle + turn * TURN_SPEED * DT);

        if (in.dash() && p.dashCd <= 0) {
            p.dashTime = DASH_TIME;
            p.dashCd = DASH_COOLDOWN;
        }
        double speed = p.dashTime > 0 ? DASH_SPEED
                : in.forward() ? MOVE_SPEED
                : in.back() ? -BACK_SPEED
                : 0;
        double k = p.dashTime > 0 ? 1 : 0.25;
        p.vx += (Math.cos(p.angle) * speed - p.vx) * k;
        p.vy += (Math.sin(p.angle) * speed - p.vy) * k;
        p.x += p.vx * DT;
        p.y += p.vy * DT;
        resolveCollisions(p);

        if (in.fire() && p.fireCd <= 0 && phase == Phase.PLAYING) {
            p.fireCd = p.overclock > 0 ? OVERCLOCK_FIRE_COOLDOWN : FIRE_COOLDOWN;
            fire(p);
        }
    }

    // ------------------------------------------------------------------ collisions

    private boolean solid(int i) {
        return obstacles.get(i).mirror() || crateHp[i] > 0;
    }

    private void resolveCollisions(Player p) {
        double r = SHIP_RADIUS;
        for (int i = 0; i < obstacles.size(); i++) {
            if (!solid(i)) continue;
            Obstacle o = obstacles.get(i);
            double cx = clamp(p.x, o.x(), o.x() + o.w());
            double cy = clamp(p.y, o.y(), o.y() + o.h());
            double dx = p.x - cx, dy = p.y - cy;
            double d2 = dx * dx + dy * dy;
            if (d2 >= r * r) continue;
            if (d2 > 1e-9) {
                double d = Math.sqrt(d2);
                p.x = cx + dx / d * r;
                p.y = cy + dy / d * r;
            } else {
                // centre is inside the box: push out through the nearest face
                double left = p.x - o.x(), right = o.x() + o.w() - p.x;
                double top = p.y - o.y(), bottom = o.y() + o.h() - p.y;
                double m = Math.min(Math.min(left, right), Math.min(top, bottom));
                if (m == left) p.x = o.x() - r;
                else if (m == right) p.x = o.x() + o.w() + r;
                else if (m == top) p.y = o.y() - r;
                else p.y = o.y() + o.h() + r;
            }
        }
        p.x = clamp(p.x, r, map.width() - r);
        p.y = clamp(p.y, r, map.height() - r);
    }

    private void separatePlayers() {
        List<Player> alive = players.values().stream().filter(p -> p.alive).toList();
        double min = SHIP_RADIUS * 2;
        for (int i = 0; i < alive.size(); i++) {
            for (int j = i + 1; j < alive.size(); j++) {
                Player a = alive.get(i), b = alive.get(j);
                double dx = b.x - a.x, dy = b.y - a.y;
                double d = Math.hypot(dx, dy);
                if (d < min && d > 1e-6) {
                    double push = (min - d) / 2;
                    a.x -= dx / d * push;
                    a.y -= dy / d * push;
                    b.x += dx / d * push;
                    b.y += dy / d * push;
                }
            }
        }
    }

    private boolean anyPlayerInside(Obstacle o) {
        for (Player p : players.values()) {
            if (p.alive && p.x > o.x() - SHIP_RADIUS && p.x < o.x() + o.w() + SHIP_RADIUS
                    && p.y > o.y() - SHIP_RADIUS && p.y < o.y() + o.h() + SHIP_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ lasers

    private enum HitKind { WALL, MIRROR, CRATE, PLAYER }

    private record Hit(double t, HitKind kind, boolean verticalSurface, int index, Player player) {}

    private void fire(Player shooter) {
        double x = shooter.x, y = shooter.y;
        double dx = Math.cos(shooter.angle), dy = Math.sin(shooter.angle);
        double remaining = BEAM_RANGE;
        int bounces = 0;
        List<Float> pts = new ArrayList<>();
        pts.add((float) (x + dx * SHIP_RADIUS));
        pts.add((float) (y + dy * SHIP_RADIUS));

        while (true) {
            Hit hit = trace(x, y, dx, dy, remaining, shooter, bounces == 0);
            if (hit == null) {
                pts.add((float) (x + dx * remaining));
                pts.add((float) (y + dy * remaining));
                break;
            }
            x += dx * hit.t();
            y += dy * hit.t();
            pts.add((float) x);
            pts.add((float) y);
            remaining -= hit.t();

            int damage = (int) Math.round(BEAM_DAMAGE * (1 + BOUNCE_BONUS * bounces));
            if (hit.kind() == HitKind.WALL || hit.kind() == HitKind.MIRROR) {
                if (bounces == MAX_BOUNCES || remaining <= 0) break;
                if (hit.verticalSurface()) dx = -dx;
                else dy = -dy;
                bounces++;
                continue;
            }
            if (hit.kind() == HitKind.CRATE) {
                damageCrate(hit.index(), damage, shooter);
            } else {
                damagePlayer(hit.player(), damage, shooter, bounces);
            }
            break;
        }

        float[] arr = new float[pts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = pts.get(i);
        beams.add(new Beam(nextBeamId++, shooter.id, shooter.color, arr, bounces, time));
    }

    /** Nearest thing the ray (x,y)+t*(dx,dy), 0 < t <= maxT, runs into. */
    private Hit trace(double x, double y, double dx, double dy, double maxT, Player shooter, boolean ignoreShooter) {
        Hit best = null;

        // arena walls (always reflect)
        if (dx > 0) best = closer(best, new Hit((map.width() - x) / dx, HitKind.WALL, true, -1, null));
        if (dx < 0) best = closer(best, new Hit(-x / dx, HitKind.WALL, true, -1, null));
        if (dy > 0) best = closer(best, new Hit((map.height() - y) / dy, HitKind.WALL, false, -1, null));
        if (dy < 0) best = closer(best, new Hit(-y / dy, HitKind.WALL, false, -1, null));

        for (int i = 0; i < obstacles.size(); i++) {
            if (!solid(i)) continue;
            Obstacle o = obstacles.get(i);
            Hit h = rayBox(x, y, dx, dy, o, i);
            best = closer(best, h);
        }

        for (Player p : players.values()) {
            if (!p.alive || (ignoreShooter && p == shooter)) continue;
            double r = SHIP_RADIUS + 2;
            double fx = x - p.x, fy = y - p.y;
            double b = fx * dx + fy * dy;
            double c = fx * fx + fy * fy - r * r;
            double disc = b * b - c;
            if (disc < 0) continue;
            double t = -b - Math.sqrt(disc);
            if (t > EPS) best = closer(best, new Hit(t, HitKind.PLAYER, false, -1, p));
        }

        return best != null && best.t() <= maxT ? best : null;
    }

    private Hit rayBox(double x, double y, double dx, double dy, Obstacle o, int index) {
        double x0 = o.x(), x1 = o.x() + o.w(), y0 = o.y(), y1 = o.y() + o.h();
        double txNear, txFar, tyNear, tyFar;
        if (Math.abs(dx) < 1e-12) {
            if (x < x0 || x > x1) return null;
            txNear = Double.NEGATIVE_INFINITY;
            txFar = Double.POSITIVE_INFINITY;
        } else {
            double a = (x0 - x) / dx, b = (x1 - x) / dx;
            txNear = Math.min(a, b);
            txFar = Math.max(a, b);
        }
        if (Math.abs(dy) < 1e-12) {
            if (y < y0 || y > y1) return null;
            tyNear = Double.NEGATIVE_INFINITY;
            tyFar = Double.POSITIVE_INFINITY;
        } else {
            double a = (y0 - y) / dy, b = (y1 - y) / dy;
            tyNear = Math.min(a, b);
            tyFar = Math.max(a, b);
        }
        double enter = Math.max(txNear, tyNear);
        double exit = Math.min(txFar, tyFar);
        if (enter > exit || enter <= EPS) return null;
        HitKind kind = o.mirror() ? HitKind.MIRROR : HitKind.CRATE;
        return new Hit(enter, kind, txNear > tyNear, index, null);
    }

    private static Hit closer(Hit a, Hit b) {
        if (b == null || b.t() <= EPS) return a;
        return a == null || b.t() < a.t() ? b : a;
    }

    private void damageCrate(int index, int damage, Player shooter) {
        crateHp[index] -= damage;
        if (crateHp[index] <= 0) {
            crateHp[index] = 0;
            crateRespawn[index] = CRATE_RESPAWN;
            Obstacle o = obstacles.get(index);
            event(Type.CRATE, shooter.name + " ran  rm " + o.label(), shooter.color,
                    o.x() + o.w() / 2, o.y() + o.h() / 2, index);
        }
    }

    private void damagePlayer(Player victim, int damage, Player shooter, int bounces) {
        if (victim.shield > 0) {
            event(Type.HIT, "", 0xFFFFFF, victim.x, victim.y, victim.id);
            return;
        }
        victim.hp -= damage;
        event(Type.HIT, "", shooter.color, victim.x, victim.y, victim.id);
        if (victim.hp > 0) return;

        victim.hp = 0;
        victim.alive = false;
        victim.respawnIn = RESPAWN_TIME;
        victim.deaths++;
        victim.streak = 0;
        victim.input = PlayerInput.NONE;

        if (shooter == victim) {
            event(Type.KILL, victim.name + " bounced their own laser into their face", victim.color,
                    victim.x, victim.y, victim.id);
            return;
        }

        shooter.kills++;
        shooter.streak++;
        shooter.multi = time - shooter.lastKillTime < 3 ? shooter.multi + 1 : 1;
        shooter.lastKillTime = time;

        String line = String.format(KILL_LINES[rnd.nextInt(KILL_LINES.length)], shooter.name, victim.name);
        if (bounces > 0) line += "  [" + bounces + "-bounce bank shot!]";
        event(Type.KILL, line, shooter.color, victim.x, victim.y, victim.id);

        if (shooter.multi == 2) event(Type.SYSTEM, shooter.name + ": DOUBLE ZAP", shooter.color, 0, 0, shooter.id);
        if (shooter.multi >= 3) event(Type.SYSTEM, shooter.name + ": MULTI ZAP x" + shooter.multi, shooter.color, 0, 0, shooter.id);
        if (shooter.streak == 3) event(Type.SYSTEM, shooter.name + " is on a streak", shooter.color, 0, 0, shooter.id);
        if (shooter.streak == 5) event(Type.SYSTEM, shooter.name + " is UNSTOPPABLE", shooter.color, 0, 0, shooter.id);

        if (shooter.kills >= killLimit) endRound(shooter);
    }

    // ------------------------------------------------------------------ pickups

    private void updatePickups() {
        if (phase != Phase.PLAYING) return;
        pickupTimer -= DT;
        if (pickupTimer <= 0 && pickups.size() < 2) {
            pickupTimer = PICKUP_INTERVAL;
            double[] pos = freeSpot(60, 30);
            if (pos != null) {
                int type = rnd.nextInt(3) == 0 ? PickupView.OVERCLOCK : PickupView.REPAIR;
                pickups.add(new Pickup(nextPickupId++, type, pos[0], pos[1]));
            }
        }
        for (Iterator<Pickup> it = pickups.iterator(); it.hasNext(); ) {
            Pickup k = it.next();
            for (Player p : players.values()) {
                if (!p.alive || Math.hypot(p.x - k.x(), p.y - k.y()) > SHIP_RADIUS + 14) continue;
                if (k.type() == PickupView.REPAIR) {
                    p.hp = Math.min(MAX_HP, p.hp + 50);
                    event(Type.PICKUP, p.name + " applied a hotfix (+50 HP)", p.color, k.x(), k.y(), p.id);
                } else {
                    p.overclock = OVERCLOCK_TIME;
                    event(Type.PICKUP, p.name + " is OVERCLOCKED", p.color, k.x(), k.y(), p.id);
                }
                it.remove();
                break;
            }
        }
    }

    /** Random point at least {@code clearance} away from walls and obstacles. */
    private double[] freeSpot(double margin, double clearance) {
        for (int attempt = 0; attempt < 50; attempt++) {
            double x = margin + rnd.nextDouble() * (map.width() - 2 * margin);
            double y = margin + rnd.nextDouble() * (map.height() - 2 * margin);
            boolean ok = true;
            for (int i = 0; i < obstacles.size() && ok; i++) {
                Obstacle o = obstacles.get(i);
                ok = x < o.x() - clearance || x > o.x() + o.w() + clearance
                        || y < o.y() - clearance || y > o.y() + o.h() + clearance;
            }
            if (ok) return new double[]{x, y};
        }
        return null;
    }

    // ------------------------------------------------------------------ rounds & spawning

    private void startRound() {
        phase = Phase.PLAYING;
        phaseTime = roundSeconds;
        winner = "";
        beams.clear();
        pickups.clear();
        pickupTimer = 4;
        resetCrates();
        for (Player p : players.values()) {
            p.kills = p.deaths = p.streak = 0;
            p.hp = MAX_HP;
            spawn(p);
            p.alive = true;
        }
        event(Type.ROUND, "Round started: first to " + killLimit + " zaps wins", 0xFFFFFF, 0, 0, 0);
    }

    private void endRound(Player best) {
        phase = Phase.INTERMISSION;
        phaseTime = INTERMISSION;
        winner = best == null ? "nobody" : best.name;
        event(Type.ROUND, winner + " wins the round!", best == null ? 0xFFFFFF : best.color, 0, 0,
                best == null ? 0 : best.id);
    }

    private Player leader() {
        Player best = null;
        for (Player p : players.values()) {
            if (best == null || p.kills > best.kills || (p.kills == best.kills && p.deaths < best.deaths)) {
                best = p;
            }
        }
        return best == null || best.kills == 0 ? null : best;
    }

    private void resetCrates() {
        for (int i = 0; i < obstacles.size(); i++) {
            crateHp[i] = obstacles.get(i).mirror() ? 0 : obstacles.get(i).maxHp();
            crateRespawn[i] = 0;
        }
    }

    private void spawn(Player p) {
        double w = map.width(), h = map.height();
        double[][] spots = {
                {70, 70}, {w - 70, 70}, {70, h - 70}, {w - 70, h - 70},
                {w / 2, 55}, {w / 2, h - 55}, {55, h / 2}, {w - 55, h / 2},
        };
        double[] best = spots[0];
        double bestScore = -1;
        for (double[] s : spots) {
            double nearest = 5000;
            for (Player o : players.values()) {
                if (o != p && o.alive) nearest = Math.min(nearest, Math.hypot(o.x - s[0], o.y - s[1]));
            }
            double score = nearest + rnd.nextDouble() * 80;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        p.x = best[0];
        p.y = best[1];
        p.vx = p.vy = 0;
        p.angle = Math.atan2(h / 2 - p.y, w / 2 - p.x);
        p.hp = MAX_HP;
        p.shield = SPAWN_SHIELD;
        p.fireCd = 0.3;
        p.dashTime = 0;
        p.overclock = 0;
        resolveCollisions(p);
    }

    // ------------------------------------------------------------------ snapshot

    private GameSnapshot buildSnapshot() {
        List<PlayerView> pv = new ArrayList<>(players.size());
        for (Player p : players.values()) {
            pv.add(new PlayerView(p.id, p.name, p.color, p.x, p.y, p.angle, Math.max(0, p.hp),
                    p.kills, p.deaths, p.streak, p.alive, p.shield > 0, p.overclock > 0, p.dashTime > 0,
                    Math.max(0, p.respawnIn), p.fireCd, p.dashCd));
        }
        List<BeamView> bv = new ArrayList<>(beams.size());
        for (Beam b : beams) {
            bv.add(new BeamView(b.id(), b.ownerId(), b.color(), b.points(), time - b.born(), b.bounces()));
        }
        List<PickupView> kv = new ArrayList<>(pickups.size());
        for (Pickup k : pickups) {
            kv.add(new PickupView(k.id(), k.type(), k.x(), k.y()));
        }
        return new GameSnapshot(tick, pv, bv, kv, crateHp.clone(), phase, Math.max(0, phaseTime),
                killLimit, winner);
    }

    private void event(Type type, String text, int color, double x, double y, int subject) {
        pendingEvents.add(new GameEvent(type, text, color, x, y, subject));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static double normalize(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}

package arena.client;

import arena.common.BeamView;
import arena.common.FileEntry;
import arena.common.Frame;
import arena.common.GameEvent;
import arena.common.GameSnapshot;
import arena.common.GameSnapshot.Phase;
import arena.common.MapData;
import arena.common.Obstacle;
import arena.common.PickupView;
import arena.common.PlayerView;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import static arena.common.GameRules.TICK_RATE;

/**
 * Renders the arena. Frames arrive on RMI threads via {@link #acceptFrame};
 * everything else runs on the Swing event thread at ~60 fps, interpolating
 * between the two most recent server snapshots so 30 Hz updates look smooth.
 */
public final class ArenaPanel extends JPanel {

    private static final long TICK_NANOS = 1_000_000_000L / TICK_RATE;
    private static final double BEAM_LIFE = 0.4;
    private static final double FIRE_COOLDOWN = 0.45;
    private static final double DASH_COOLDOWN = 2.2;

    private static final Color BG = new Color(0x070A12);
    private static final Color FLOOR = new Color(0x0B1020);
    private static final Color GRID = new Color(0x141C33);
    private static final Color MIRROR = new Color(0x78E1FF);
    private static final Color CRATE = new Color(0xFFAA3C);
    private static final Color PANEL_BG = new Color(8, 12, 24, 200);
    private static final Color DIM = new Color(0x8A93B0);

    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font NAME = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font FEED = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font MED = new Font(Font.SANS_SERIF, Font.BOLD, 24);
    private static final Font BIG = new Font(Font.SANS_SERIF, Font.BOLD, 56);

    private final MapData map;
    private final int myId;
    private final Sfx sfx;
    private final Random rnd = new Random();

    // written by RMI threads
    private final Object lock = new Object();
    private GameSnapshot prev, curr;
    private long currArrival;
    private final ConcurrentLinkedQueue<GameEvent> incoming = new ConcurrentLinkedQueue<>();

    // Swing thread only
    private record FeedLine(String text, Color color, long born) {}
    private record Banner(String text, Color color, long born, double seconds, Font font) {}
    private record Bubble(String text, long born) {}
    private static final class Particle {
        double x, y, vx, vy, life, max;
        Color color;
        float size;
    }

    private final List<FeedLine> feed = new ArrayList<>();
    private final List<Banner> banners = new ArrayList<>();
    private final Map<Integer, Bubble> bubbles = new HashMap<>();
    private final List<Particle> particles = new ArrayList<>();
    private final Set<Long> seenBeams = new HashSet<>();
    private double shake;
    private double hitFlash;
    private String lastDeathLine = "";
    private long lastPaint;

    private Supplier<List<String>> hudLines = List::of;
    private boolean showHud = true;
    private FileBrowser browser;
    private volatile String status;

    public ArenaPanel(MapData map, int myId, Sfx sfx) {
        this.map = map;
        this.myId = myId;
        this.sfx = sfx;
        setBackground(BG);
        setPreferredSize(new Dimension(1280, 720));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setDoubleBuffered(true);
        new Timer(15, e -> repaint()).start();
    }

    // ------------------------------------------------------------------ inputs from other threads

    /** Called from the RMI callback thread (push) or the polling thread (pull). */
    public void acceptFrame(Frame frame) {
        synchronized (lock) {
            if (curr == null || frame.snapshot().tick() > curr.tick()) {
                prev = curr;
                curr = frame.snapshot();
                currArrival = System.nanoTime();
            }
        }
        incoming.addAll(frame.events());
    }

    /** A client-side message for the kill feed (e.g. "switched to PULL mode"). */
    public void localMessage(String text) {
        incoming.add(new GameEvent(GameEvent.Type.SYSTEM, text, 0xFFE066, 0, 0, -1));
    }

    public void setHudLines(Supplier<List<String>> lines) {
        this.hudLines = lines;
    }

    public void toggleHud() {
        showHud = !showHud;
    }

    public void setFileBrowser(FileBrowser browser) {
        this.browser = browser;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        long now = System.nanoTime();
        double dt = lastPaint == 0 ? 0.016 : Math.min(0.1, (now - lastPaint) / 1e9);
        lastPaint = now;

        GameSnapshot a, b;
        long arrival;
        synchronized (lock) {
            a = prev;
            b = curr;
            arrival = currArrival;
        }

        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth(), h = getHeight();

        if (b == null) {
            g.setFont(MED);
            g.setColor(Color.WHITE);
            centered(g, "Waiting for the first frame from the server...", w / 2, h / 2);
            drawStatus(g, w, h);
            g.dispose();
            return;
        }

        processEvents(b, now);
        double alpha = Math.max(0, Math.min(1, (now - arrival) / (double) TICK_NANOS));
        List<PlayerView> players = interpolate(a, b, alpha);
        beamSounds(b);

        double scale = Math.min(w / (double) map.width(), h / (double) map.height());
        double ox = (w - map.width() * scale) / 2, oy = (h - map.height() * scale) / 2;
        shake = Math.max(0, shake - dt * 30);
        hitFlash = Math.max(0, hitFlash - dt * 2.5);
        double sx = (rnd.nextDouble() - 0.5) * shake, sy = (rnd.nextDouble() - 0.5) * shake;

        AffineTransform screen = g.getTransform();
        g.translate(ox + sx, oy + sy);
        g.scale(scale, scale);
        drawFloor(g);
        drawObstacles(g, b);
        drawPickups(g, b, now);
        drawBeams(g, b, alpha / TICK_RATE);
        drawPlayers(g, players, now);
        drawParticles(g, dt);
        drawBubbles(g, players, now);
        g.setTransform(screen);

        drawHitFlash(g, w, h);
        drawScoreboard(g, b);
        drawTimer(g, w, b);
        drawFeed(g, w, now);
        drawMyBars(g, w, h, players);
        drawCenterMessages(g, w, h, b, players, now);
        if (showHud) drawHud(g, h);
        drawControls(g, w, h);
        if (browser != null && browser.visible) drawBrowser(g, w, h);
        drawStatus(g, w, h);
        g.dispose();
    }

    private List<PlayerView> interpolate(GameSnapshot a, GameSnapshot b, double t) {
        if (a == null) return b.players();
        Map<Integer, PlayerView> before = new HashMap<>();
        for (PlayerView p : a.players()) before.put(p.id(), p);
        List<PlayerView> out = new ArrayList<>(b.players().size());
        for (PlayerView p : b.players()) {
            PlayerView o = before.get(p.id());
            if (o == null || !o.alive() || !p.alive() || Math.hypot(p.x() - o.x(), p.y() - o.y()) > 120) {
                out.add(p);
                continue;
            }
            double da = p.angle() - o.angle();
            while (da > Math.PI) da -= 2 * Math.PI;
            while (da < -Math.PI) da += 2 * Math.PI;
            out.add(new PlayerView(p.id(), p.name(), p.colorRgb(),
                    o.x() + (p.x() - o.x()) * t, o.y() + (p.y() - o.y()) * t, o.angle() + da * t,
                    p.hp(), p.kills(), p.deaths(), p.streak(), p.alive(), p.shielded(), p.overclocked(),
                    p.dashing(), p.respawnIn(), p.fireCooldown(), p.dashCooldown()));
        }
        return out;
    }

    // ------------------------------------------------------------------ events -> effects

    private void processEvents(GameSnapshot s, long now) {
        GameEvent e;
        while ((e = incoming.poll()) != null) {
            Color c = new Color(e.colorRgb());
            switch (e.type()) {
                case HIT -> {
                    burst(e.x(), e.y(), c, 10, 160, 0.35, 2.5f);
                    if (e.subjectId() == myId) {
                        shake = Math.max(shake, 7);
                        hitFlash = 1;
                        if (sfx != null) sfx.play(Sfx.HIT, 1f);
                    }
                }
                case KILL -> {
                    Color victim = colorOf(s, e.subjectId(), c);
                    burst(e.x(), e.y(), victim, 46, 320, 0.9, 3.5f);
                    burst(e.x(), e.y(), Color.WHITE, 14, 120, 0.5, 2f);
                    addFeed(e.text(), c, now);
                    if (e.subjectId() == myId) {
                        shake = 16;
                        lastDeathLine = e.text();
                    }
                    if (sfx != null) sfx.play(Sfx.BOOM, e.subjectId() == myId ? 1f : 0.6f);
                }
                case CRATE -> {
                    burst(e.x(), e.y(), CRATE, 30, 220, 0.8, 3f);
                    addFeed(e.text(), c, now);
                    if (sfx != null) sfx.play(Sfx.CRUNCH, 0.7f);
                }
                case PICKUP -> {
                    burst(e.x(), e.y(), c, 24, 140, 0.6, 2.5f);
                    addFeed(e.text(), c, now);
                    if (sfx != null && e.subjectId() == myId) sfx.play(Sfx.PICKUP, 0.9f);
                }
                case TAUNT -> {
                    String text = e.text();
                    int colon = text.indexOf(": ");
                    bubbles.put(e.subjectId(), new Bubble(colon >= 0 ? text.substring(colon + 2) : text, now));
                    addFeed(text, c, now);
                }
                case ROUND -> {
                    banners.add(new Banner(e.text(), c, now, 3, MED));
                    addFeed(e.text(), c, now);
                    if (sfx != null) sfx.play(Sfx.ROUND, 0.8f);
                }
                case SYSTEM -> {
                    if (e.subjectId() > 0) banners.add(new Banner(e.text(), c, now, 1.8, MED));
                    addFeed(e.text(), c, now);
                }
                case JOIN, LEAVE -> addFeed(e.text(), c, now);
            }
        }
    }

    private void addFeed(String text, Color color, long now) {
        feed.add(new FeedLine(text, color, now));
        while (feed.size() > 8) feed.remove(0);
    }

    private void beamSounds(GameSnapshot s) {
        if (seenBeams.size() > 400) {
            seenBeams.clear();
            for (BeamView bm : s.beams()) seenBeams.add(bm.id());
            return;
        }
        for (BeamView bm : s.beams()) {
            if (seenBeams.add(bm.id()) && sfx != null) {
                sfx.play(Sfx.LASER, bm.ownerId() == myId ? 0.9f : 0.45f);
            }
        }
    }

    private static Color colorOf(GameSnapshot s, int playerId, Color fallback) {
        for (PlayerView p : s.players()) {
            if (p.id() == playerId) return new Color(p.colorRgb());
        }
        return fallback;
    }

    private void burst(double x, double y, Color c, int n, double speed, double life, float size) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double ang = rnd.nextDouble() * Math.PI * 2, v = speed * (0.3 + rnd.nextDouble() * 0.7);
            p.x = x;
            p.y = y;
            p.vx = Math.cos(ang) * v;
            p.vy = Math.sin(ang) * v;
            p.max = p.life = life * (0.5 + rnd.nextDouble() * 0.5);
            p.color = c;
            p.size = size * (0.6f + rnd.nextFloat() * 0.8f);
            particles.add(p);
        }
    }

    // ------------------------------------------------------------------ world layer

    private void drawFloor(Graphics2D g) {
        g.setColor(FLOOR);
        g.fillRect(0, 0, map.width(), map.height());
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1));
        for (int x = 40; x < map.width(); x += 40) g.drawLine(x, 0, x, map.height());
        for (int y = 40; y < map.height(); y += 40) g.drawLine(0, y, map.width(), y);
        g.setColor(new Color(120, 225, 255, 40));
        g.setStroke(new BasicStroke(8));
        g.drawRect(0, 0, map.width(), map.height());
        g.setColor(MIRROR);
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, map.width() - 2, map.height() - 2);
    }

    private void drawObstacles(Graphics2D g, GameSnapshot s) {
        int[] hp = s.crateHp();
        g.setFont(LABEL);
        for (Obstacle o : map.obstacles()) {
            double cx = o.x() + o.w() / 2;
            if (o.mirror()) {
                RoundRectangle2D r = new RoundRectangle2D.Double(o.x(), o.y(), o.w(), o.h(), 6, 6);
                g.setColor(new Color(120, 225, 255, 35));
                g.fill(r);
                g.setStroke(new BasicStroke(7));
                g.draw(r);
                g.setStroke(new BasicStroke(2));
                g.setColor(MIRROR);
                g.draw(r);
                g.setColor(new Color(255, 255, 255, 120));
                boolean horizontal = o.w() > o.h();
                g.draw(new java.awt.geom.Line2D.Double(o.x() + 4, o.y() + 4,
                        horizontal ? o.x() + o.w() - 4 : o.x() + 4,
                        horizontal ? o.y() + 4 : o.y() + o.h() - 4));
                g.setColor(new Color(150, 230, 255, 210));
                centered(g, ellipsize(o.label(), 18), cx, o.y() - 5);
                continue;
            }

            int h = o.id() < hp.length ? hp[o.id()] : 0;
            if (h <= 0) {
                g.setColor(new Color(255, 255, 255, 35));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 5}, 0));
                g.draw(new Rectangle2D.Double(o.x(), o.y(), o.w(), o.h()));
                g.setColor(new Color(255, 255, 255, 70));
                centered(g, "rm'd", cx, o.y() + o.h() / 2 + 4);
                continue;
            }

            double ratio = h / (double) o.maxHp();
            Color edge = blend(new Color(0xFF4040), CRATE, ratio);
            double c = o.w() * 0.28;
            Path2D file = new Path2D.Double();
            file.moveTo(o.x(), o.y());
            file.lineTo(o.x() + o.w() - c, o.y());
            file.lineTo(o.x() + o.w(), o.y() + c);
            file.lineTo(o.x() + o.w(), o.y() + o.h());
            file.lineTo(o.x(), o.y() + o.h());
            file.closePath();
            g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 40));
            g.fill(file);
            g.setStroke(new BasicStroke(2));
            g.setColor(edge);
            g.draw(file);
            Path2D fold = new Path2D.Double();
            fold.moveTo(o.x() + o.w() - c, o.y());
            fold.lineTo(o.x() + o.w() - c, o.y() + c);
            fold.lineTo(o.x() + o.w(), o.y() + c);
            g.draw(fold);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 120));
            for (int i = 1; i <= 3; i++) {
                double ly = o.y() + c + i * (o.h() - c) / 4.5;
                double len = (o.w() - 12) * (i == 3 ? 0.55 : 1);
                g.draw(new java.awt.geom.Line2D.Double(o.x() + 6, ly, o.x() + 6 + len, ly));
            }
            if (ratio < 1) {
                g.setColor(new Color(0, 0, 0, 160));
                g.fill(new Rectangle2D.Double(o.x(), o.y() + o.h() + 3, o.w(), 3));
                g.setColor(edge);
                g.fill(new Rectangle2D.Double(o.x(), o.y() + o.h() + 3, o.w() * ratio, 3));
            }
            g.setColor(new Color(255, 210, 160, 220));
            centered(g, ellipsize(o.label(), 16), cx, o.y() + o.h() + 16);
        }
    }

    private void drawPickups(Graphics2D g, GameSnapshot s, long now) {
        double pulse = 1 + 0.15 * Math.sin(now / 1.5e8);
        for (PickupView k : s.pickups()) {
            Color c = k.type() == PickupView.REPAIR ? new Color(0x7CFF4F) : new Color(0xFFE066);
            double r = 12 * pulse;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
            g.fill(new Ellipse2D.Double(k.x() - r - 6, k.y() - r - 6, 2 * r + 12, 2 * r + 12));
            g.setColor(c);
            g.setStroke(new BasicStroke(2));
            g.draw(new Ellipse2D.Double(k.x() - r, k.y() - r, 2 * r, 2 * r));
            g.setFont(NAME);
            centered(g, k.type() == PickupView.REPAIR ? "+" : ">>", k.x(), k.y() + 5);
            g.setFont(LABEL);
            centered(g, k.type() == PickupView.REPAIR ? "hotfix" : "overclock", k.x(), k.y() + r + 13);
        }
    }

    private void drawBeams(Graphics2D g, GameSnapshot s, double extraAge) {
        for (BeamView bm : s.beams()) {
            double life = 1 - (bm.age() + extraAge) / BEAM_LIFE;
            if (life <= 0) continue;
            float[] p = bm.points();
            Path2D path = new Path2D.Float();
            path.moveTo(p[0], p[1]);
            for (int i = 2; i + 1 < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
            Color c = new Color(bm.colorRgb());
            float width = (float) (0.6 + life * 0.4);
            g.setStroke(new BasicStroke(14 * width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(c, 0.10 * life));
            g.draw(path);
            g.setStroke(new BasicStroke(6 * width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(c, 0.35 * life));
            g.draw(path);
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(c.brighter(), life));
            g.draw(path);
            g.setStroke(new BasicStroke(1f));
            g.setColor(alpha(Color.WHITE, 0.9 * life));
            g.draw(path);
            for (int i = 2; i + 3 < p.length; i += 2) {
                double r = 7 * life + 2;
                g.setColor(alpha(Color.WHITE, 0.7 * life));
                g.fill(new Ellipse2D.Double(p[i] - r, p[i + 1] - r, 2 * r, 2 * r));
            }
        }
    }

    private void drawPlayers(Graphics2D g, List<PlayerView> players, long now) {
        for (PlayerView p : players) {
            if (!p.alive()) continue;
            Color c = new Color(p.colorRgb());
            AffineTransform t = g.getTransform();
            g.translate(p.x(), p.y());

            g.setColor(alpha(c, 0.16));
            g.fill(new Ellipse2D.Double(-28, -28, 56, 56));
            if (p.overclocked()) {
                double r = 24 + 3 * Math.sin(now / 6e7);
                g.setColor(new Color(255, 224, 102, 160));
                g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 4}, (float) ((now / 2e7) % 8)));
                g.draw(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
            }

            g.rotate(p.angle());
            if (p.dashing()) {
                for (int i = 1; i <= 3; i++) {
                    g.setColor(alpha(c, 0.25 / i));
                    g.fill(ship(-i * 12));
                }
            }
            Path2D body = ship(0);
            g.setColor(c.darker().darker());
            g.fill(body);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(c);
            g.draw(body);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(1, -3, 6, 6));
            g.setTransform(t);

            if (p.shielded()) {
                double r = 25 + 2 * Math.sin(now / 5e7);
                g.setColor(new Color(255, 255, 255, 140));
                g.setStroke(new BasicStroke(2));
                g.draw(new Ellipse2D.Double(p.x() - r, p.y() - r, 2 * r, 2 * r));
            }

            // name + HP above the ship, or below it when hugging the top wall
            boolean below = p.y() < 50;
            double barY = below ? p.y() + 28 : p.y() - 30;
            double nameY = below ? p.y() + 46 : p.y() - 36;
            double barW = 38;
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(new Rectangle2D.Double(p.x() - barW / 2, barY, barW, 4));
            g.setColor(blend(new Color(0xFF4040), new Color(0x7CFF4F), p.hp() / 100.0));
            g.fill(new Rectangle2D.Double(p.x() - barW / 2, barY, barW * p.hp() / 100.0, 4));
            g.setFont(NAME);
            g.setColor(c);
            String label = p.id() == myId ? "> " + p.name() + " <" : p.name();
            int half = g.getFontMetrics().stringWidth(label) / 2 + 2;
            shadowed(g, label, Math.max(half, Math.min(map.width() - half, p.x())), nameY);
        }
    }

    private static Path2D ship(double offset) {
        Path2D s = new Path2D.Double();
        s.moveTo(20 + offset, 0);
        s.lineTo(-13 + offset, -12);
        s.lineTo(-7 + offset, 0);
        s.lineTo(-13 + offset, 12);
        s.closePath();
        return s;
    }

    private void drawParticles(Graphics2D g, double dt) {
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 1 - 3 * dt;
            p.vy *= 1 - 3 * dt;
            double a = p.life / p.max;
            g.setColor(alpha(p.color, a));
            double r = p.size * (0.5 + a);
            g.fill(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
        }
    }

    private void drawBubbles(Graphics2D g, List<PlayerView> players, long now) {
        g.setFont(NAME);
        FontMetrics fm = g.getFontMetrics();
        bubbles.entrySet().removeIf(e -> now - e.getValue().born() > 3_500_000_000L);
        for (PlayerView p : players) {
            Bubble b = bubbles.get(p.id());
            if (b == null || !p.alive()) continue;
            int tw = fm.stringWidth(b.text());
            double bx = Math.max(4, Math.min(map.width() - tw - 20, p.x() - tw / 2.0 - 8));
            double by = p.y() < 80 ? p.y() + 54 : p.y() - 72;
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new RoundRectangle2D.Double(bx, by, tw + 16, 22, 10, 10));
            double edge = by > p.y() ? by : by + 22, tip = by > p.y() ? by - 8 : by + 30;
            Path2D tail = new Path2D.Double();
            tail.moveTo(p.x() - 5, edge);
            tail.lineTo(p.x() + 5, edge);
            tail.lineTo(p.x(), tip);
            tail.closePath();
            g.fill(tail);
            g.setColor(new Color(0x10131F));
            g.drawString(b.text(), (float) (bx + 8), (float) (by + 16));
        }
    }

    // ------------------------------------------------------------------ HUD layer

    private void drawHitFlash(Graphics2D g, int w, int h) {
        if (hitFlash <= 0) return;
        int a = (int) (120 * hitFlash);
        int edge = Math.max(40, Math.min(w, h) / 6);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 0, 40, a), edge, 0, new Color(255, 0, 40, 0)));
        g.fillRect(0, 0, edge, h);
        g.setPaint(new GradientPaint(w, 0, new Color(255, 0, 40, a), w - edge, 0, new Color(255, 0, 40, 0)));
        g.fillRect(w - edge, 0, edge, h);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 0, 40, a), 0, edge, new Color(255, 0, 40, 0)));
        g.fillRect(0, 0, w, edge);
        g.setPaint(new GradientPaint(0, h, new Color(255, 0, 40, a), 0, h - edge, new Color(255, 0, 40, 0)));
        g.fillRect(0, h - edge, w, edge);
    }

    private void drawScoreboard(Graphics2D g, GameSnapshot s) {
        List<PlayerView> sorted = new ArrayList<>(s.players());
        sorted.sort(Comparator.comparingInt(PlayerView::kills).reversed().thenComparingInt(PlayerView::deaths));
        int x = 12, y = 12, rowH = 20, width = 230;
        int height = 30 + rowH * Math.max(1, sorted.size());
        g.setColor(PANEL_BG);
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 12, 12));
        g.setFont(MONO_BOLD);
        g.setColor(DIM);
        g.drawString("PLAYER", x + 26, y + 20);
        g.drawString("ZAPS  DIED", x + 140, y + 20);
        int row = y + 22;
        for (PlayerView p : sorted) {
            row += rowH;
            Color c = new Color(p.colorRgb());
            g.setColor(c);
            g.fillRect(x + 10, row - 11, 9, 9);
            g.setFont(p.id() == myId ? MONO_BOLD : MONO);
            g.drawString(ellipsize(p.name(), 12), x + 26, row - 2);
            g.setColor(Color.WHITE);
            g.drawString(String.format("%4d  %4d", p.kills(), p.deaths()), x + 140, row - 2);
        }
        if (sorted.isEmpty()) {
            g.setFont(MONO);
            g.setColor(DIM);
            g.drawString("(nobody here yet)", x + 26, row + 18);
        }
    }

    private void drawTimer(Graphics2D g, int w, GameSnapshot s) {
        String main, sub;
        switch (s.phase()) {
            case PLAYING -> {
                int secs = (int) Math.ceil(s.phaseTimeLeft());
                main = String.format("%d:%02d", secs / 60, secs % 60);
                sub = "first to " + s.killLimit() + " zaps";
            }
            case INTERMISSION -> {
                main = "NEXT ROUND IN " + (int) Math.ceil(s.phaseTimeLeft());
                sub = "";
            }
            default -> {
                main = "WAITING FOR PLAYERS";
                sub = "";
            }
        }
        g.setFont(MED);
        g.setColor(Color.WHITE);
        shadowed(g, main, w / 2.0, 36);
        if (!sub.isEmpty()) {
            g.setFont(MONO);
            g.setColor(DIM);
            centered(g, sub + "   tick #" + s.tick(), w / 2.0, 54);
        }
    }

    private void drawFeed(Graphics2D g, int w, long now) {
        g.setFont(FEED);
        FontMetrics fm = g.getFontMetrics();
        int y = 16;
        for (FeedLine line : feed) {
            double age = (now - line.born()) / 1e9;
            if (age > 8) continue;
            double a = age > 6 ? (8 - age) / 2 : 1;
            int tw = fm.stringWidth(line.text());
            g.setColor(new Color(8, 12, 24, (int) (190 * a)));
            g.fill(new RoundRectangle2D.Double(w - tw - 30, y, tw + 18, 22, 8, 8));
            g.setColor(alpha(line.color(), a));
            g.drawString(line.text(), w - tw - 21, y + 16);
            y += 26;
        }
    }

    private void drawMyBars(Graphics2D g, int w, int h, List<PlayerView> players) {
        PlayerView me = find(players, myId);
        if (me == null || !me.alive()) return;
        int bw = 240, x = w / 2 - bw / 2, y = h - 70;
        g.setColor(PANEL_BG);
        g.fill(new RoundRectangle2D.Double(x - 14, y - 12, bw + 28, 70, 12, 12));
        bar(g, x, y, bw, 10, me.hp() / 100.0, blend(new Color(0xFF4040), new Color(0x7CFF4F), me.hp() / 100.0),
                "HP " + me.hp());
        double fire = me.overclocked() ? 1 - me.fireCooldown() / 0.15 : 1 - me.fireCooldown() / FIRE_COOLDOWN;
        bar(g, x, y + 28, bw / 2 - 4, 7, fire, me.overclocked() ? new Color(0xFFE066) : new Color(me.colorRgb()),
                me.overclocked() ? "OVERCLOCK" : "LASER");
        bar(g, x + bw / 2 + 4, y + 28, bw / 2 - 4, 7, 1 - me.dashCooldown() / DASH_COOLDOWN, new Color(0x78E1FF), "DASH");
    }

    private void bar(Graphics2D g, int x, int y, int w, int h, double ratio, Color c, String label) {
        ratio = Math.max(0, Math.min(1, ratio));
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(c);
        g.fillRoundRect(x, y, (int) (w * ratio), h, h, h);
        g.setFont(LABEL);
        g.setColor(Color.WHITE);
        g.drawString(label, x, y + h + 11);
    }

    private void drawCenterMessages(Graphics2D g, int w, int h, GameSnapshot s, List<PlayerView> players, long now) {
        if (s.phase() == Phase.INTERMISSION) {
            drawResults(g, w, h, s);
        } else {
            PlayerView me = find(players, myId);
            if (me != null && !me.alive() && s.phase() == Phase.PLAYING) {
                g.setFont(BIG);
                g.setColor(new Color(0xFF3B6B));
                shadowed(g, "ZAPPED", w / 2.0, h / 2.0 - 30);
                g.setFont(FEED);
                g.setColor(Color.WHITE);
                shadowed(g, lastDeathLine, w / 2.0, h / 2.0 + 4);
                g.setFont(MED);
                shadowed(g, String.format("respawn in %.1f", me.respawnIn()), w / 2.0, h / 2.0 + 40);
            }
        }

        int y = h / 3;
        for (Iterator<Banner> it = banners.iterator(); it.hasNext(); ) {
            Banner b = it.next();
            double age = (now - b.born()) / 1e9;
            if (age > b.seconds()) {
                it.remove();
                continue;
            }
            double a = Math.min(1, Math.min(age * 6, (b.seconds() - age) * 3));
            g.setFont(b.font().deriveFont((float) (b.font().getSize() * (1 + 0.25 * Math.max(0, 0.2 - age) / 0.2))));
            g.setColor(alpha(b.color(), a));
            shadowed(g, b.text(), w / 2.0, y);
            y += 40;
        }
    }

    private void drawResults(Graphics2D g, int w, int h, GameSnapshot s) {
        List<PlayerView> sorted = new ArrayList<>(s.players());
        sorted.sort(Comparator.comparingInt(PlayerView::kills).reversed().thenComparingInt(PlayerView::deaths));
        int bw = 460, bh = 120 + 26 * sorted.size();
        int x = w / 2 - bw / 2, y = h / 2 - bh / 2;
        g.setColor(new Color(8, 12, 24, 225));
        g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 18, 18));
        g.setColor(new Color(0x78E1FF));
        g.setStroke(new BasicStroke(2));
        g.draw(new RoundRectangle2D.Double(x, y, bw, bh, 18, 18));
        g.setFont(MED);
        g.setColor(Color.WHITE);
        centered(g, s.winner() + " WINS", w / 2.0, y + 44);
        g.setFont(MONO);
        g.setColor(DIM);
        centered(g, "#   player            zaps  died", w / 2.0, y + 78);
        int row = y + 104;
        int rank = 1;
        for (PlayerView p : sorted) {
            g.setColor(new Color(p.colorRgb()));
            g.setFont(p.id() == myId ? MONO_BOLD : MONO);
            centered(g, String.format("%d   %-16s  %4d  %4d", rank++, ellipsize(p.name(), 16), p.kills(), p.deaths()),
                    w / 2.0, row);
            row += 26;
        }
    }

    private void drawHud(Graphics2D g, int h) {
        List<String> lines = hudLines.get();
        if (lines.isEmpty()) return;
        g.setFont(MONO);
        FontMetrics fm = g.getFontMetrics();
        int width = 0;
        for (String l : lines) width = Math.max(width, fm.stringWidth(l));
        int lh = 16, bh = lines.size() * lh + 14;
        int x = 12, y = h - bh - 12;
        g.setColor(PANEL_BG);
        g.fill(new RoundRectangle2D.Double(x, y, width + 24, bh, 12, 12));
        g.setColor(new Color(0x78E1FF));
        g.setStroke(new BasicStroke(1));
        g.draw(new RoundRectangle2D.Double(x, y, width + 24, bh, 12, 12));
        int ly = y + 20;
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(i == 0 ? new Color(0x78E1FF) : new Color(0xD8DEF0));
            g.drawString(lines.get(i), x + 12, ly);
            ly += lh;
        }
    }

    private void drawControls(Graphics2D g, int w, int h) {
        if (myId < 0) return;
        g.setFont(LABEL);
        g.setColor(DIM);
        String[] lines = {
                "W/S or arrows: move    A/D: turn    SPACE: fire    SHIFT: dash",
                "1-4: taunt    TAB: remote ls    F3: RMI stats    M: mute",
        };
        int y = h - 26;
        for (String l : lines) {
            g.drawString(l, w - g.getFontMetrics().stringWidth(l) - 12, y);
            y += 13;
        }
    }

    private void drawBrowser(Graphics2D g, int w, int h) {
        int bw = Math.min(760, w - 40), bh = Math.min(480, h - 80);
        int x = w / 2 - bw / 2, y = h / 2 - bh / 2;
        g.setColor(new Color(5, 8, 14, 240));
        g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 14, 14));
        g.setColor(new Color(0x7CFF4F));
        g.setStroke(new BasicStroke(2));
        g.draw(new RoundRectangle2D.Double(x, y, bw, bh, 14, 14));

        g.setFont(MONO_BOLD);
        g.setColor(new Color(0x7CFF4F));
        g.drawString("$ ls -l " + browser.displayPath(), x + 16, y + 26);
        g.setFont(MONO);
        g.setColor(DIM);
        g.drawString("remote call: FileService.list(\"" + browser.path() + "\")  on  " + browser.host(), x + 16, y + 44);

        List<FileEntry> entries = browser.entries();
        int lh = 18, top = y + 70;
        int visibleRows = Math.max(1, (bh - 110) / lh);
        int sel = browser.selected();
        int first = Math.max(0, Math.min(sel - visibleRows / 2, entries.size() - visibleRows));
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = first; i < Math.min(entries.size(), first + visibleRows); i++) {
            FileEntry e = entries.get(i);
            int ry = top + (i - first) * lh;
            if (i == sel) {
                g.setColor(new Color(124, 255, 79, 50));
                g.fillRect(x + 10, ry - 13, bw - 20, lh);
            }
            g.setColor(e.directory() ? new Color(0x78E1FF) : new Color(0xE8ECF8));
            String line = String.format("%s  %9s  %s  %s", e.directory() ? "d" : "-",
                    e.directory() ? "-" : human(e.size()), fmt.format(new Date(e.modifiedMillis())),
                    e.name() + (e.directory() ? "/" : ""));
            g.drawString(line, x + 16, ry);
        }
        if (entries.isEmpty()) {
            g.setColor(DIM);
            g.drawString("(empty)", x + 16, top);
        }
        g.setColor(new Color(0xFFE066));
        g.drawString(browser.status(), x + 16, y + bh - 30);
        g.setColor(DIM);
        g.drawString("UP/DOWN select   ENTER open folder   BACKSPACE parent   TAB/ESC close", x + 16, y + bh - 12);
    }

    private void drawStatus(Graphics2D g, int w, int h) {
        String s = status;
        if (s == null) return;
        g.setFont(MED);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(s);
        g.setColor(new Color(120, 0, 20, 220));
        g.fillRoundRect(w / 2 - tw / 2 - 20, h / 2 - 30, tw + 40, 50, 14, 14);
        g.setColor(Color.WHITE);
        centered(g, s, w / 2.0, h / 2.0 + 3);
    }

    // ------------------------------------------------------------------ helpers

    private static PlayerView find(List<PlayerView> players, int id) {
        for (PlayerView p : players) {
            if (p.id() == id) return p;
        }
        return null;
    }

    private static void centered(Graphics2D g, String text, double cx, double baseline) {
        int tw = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (float) (cx - tw / 2.0), (float) baseline);
    }

    private static void shadowed(Graphics2D g, String text, double cx, double baseline) {
        Color c = g.getColor();
        g.setColor(new Color(0, 0, 0, Math.min(200, c.getAlpha())));
        centered(g, text, cx + 2, baseline + 2);
        g.setColor(c);
        centered(g, text, cx, baseline);
    }

    private static Color alpha(Color c, double a) {
        int v = (int) Math.round(255 * Math.max(0, Math.min(1, a)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), v);
    }

    private static Color blend(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static String ellipsize(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }
}

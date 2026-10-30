package arena.server;

import arena.common.MapData;
import arena.common.Obstacle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static arena.common.GameRules.WORLD_H;
import static arena.common.GameRules.WORLD_W;

/**
 * Builds the level from a directory listing: every sub-directory becomes a
 * mirror wall that reflects lasers, every file becomes a crate whose toughness
 * grows with the file size. Same folder, same map.
 */
final class MapBuilder {
    private MapBuilder() {}

    private static final int MAX_OBSTACLES = 14;
    private static final int MIN_OBSTACLES = 8;
    private static final String[] FILLERS = {"/dev/null/", "/tmp/", "/proc/", "lost+found/"};

    private record Entry(String name, boolean dir, long size) {}

    static MapData build(Path dir) {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> entries.add(new Entry(p.getFileName().toString(), Files.isDirectory(p), size(p))));
        } catch (IOException e) {
            System.err.println("Could not list " + dir + " (" + e.getMessage() + "), using an empty map");
        }
        entries.sort(Comparator.comparing(Entry::dir).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        if (entries.size() > MAX_OBSTACLES) {
            entries.subList(MAX_OBSTACLES, entries.size()).clear();
        }
        for (int i = 0; entries.size() < MIN_OBSTACLES; i++) {
            entries.add(new Entry(FILLERS[i % FILLERS.length], true, 0));
        }

        // 5x4 grid of slots, shuffled deterministically per folder name
        List<double[]> slots = new ArrayList<>();
        for (double sy : new double[]{165, 290, 430, 555}) {
            for (double sx : new double[]{255, 450, 640, 830, 1025}) {
                slots.add(new double[]{sx, sy});
            }
        }
        Random rnd = new Random(dir.toAbsolutePath().getFileName() == null ? 42 : dir.toAbsolutePath().getFileName().hashCode());
        Collections.shuffle(slots, rnd);

        List<Obstacle> obstacles = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            double[] slot = slots.get(i);
            double jx = (rnd.nextDouble() - 0.5) * 24, jy = (rnd.nextDouble() - 0.5) * 24;
            double w, h;
            int hp;
            if (e.dir()) {
                boolean horizontal = rnd.nextBoolean();
                w = horizontal ? 140 : 18;
                h = horizontal ? 18 : 120;
                hp = 0;
            } else {
                double log = Math.log10(e.size() + 1);
                w = h = Math.max(36, Math.min(76, 34 + 7 * log));
                hp = (int) Math.max(60, Math.min(200, 60 + 15 * log));
            }
            String label = e.dir() && !e.name().endsWith("/") ? e.name() + "/" : e.name();
            obstacles.add(new Obstacle(i, label, slot[0] + jx - w / 2, slot[1] + jy - h / 2, w, h,
                    e.dir(), e.size(), hp));
        }
        return new MapData(WORLD_W, WORLD_H, List.copyOf(obstacles), dir.toAbsolutePath().normalize().toString());
    }

    private static long size(Path p) {
        try {
            return Files.isDirectory(p) ? 0 : Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}

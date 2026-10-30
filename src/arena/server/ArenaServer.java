package arena.server;

import arena.common.ArenaService;
import arena.common.FileService;
import arena.common.Frame;
import arena.common.MapData;
import arena.common.Net;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static arena.common.GameRules.REGISTRY_PORT;
import static arena.common.GameRules.SERVER_OBJECT_PORT;
import static arena.common.GameRules.TICK_RATE;

/**
 * java -jar rmi-arena.jar server [--host IP] [--dir FOLDER] [--kills N] [--minutes M] [--headless]
 */
public final class ArenaServer {

    // Strong references: an exported object that nobody references can be garbage-collected
    private static Registry registry;
    private static ArenaServiceImpl arena;
    private static FileServiceImpl files;

    private ArenaServer() {}

    public static void main(String[] args) throws Exception {
        String host = null;
        Path dir = null;
        int kills = 10;
        double minutes = 3;
        boolean headless = GraphicsEnvironment.isHeadless();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--dir" -> dir = Path.of(args[++i]);
                case "--kills" -> kills = Integer.parseInt(args[++i]);
                case "--minutes" -> minutes = Double.parseDouble(args[++i]);
                case "--headless" -> headless = true;
                default -> {
                    System.err.println("Unknown option " + args[i]);
                    System.err.println("usage: server [--host IP] [--dir FOLDER] [--kills N] [--minutes M] [--headless]");
                    System.exit(2);
                }
            }
        }

        List<Net.Candidate> candidates = Net.candidateAddresses();
        if (host == null) {
            host = candidates.isEmpty() ? "127.0.0.1" : candidates.get(0).ip();
        }
        if (dir == null) {
            dir = Files.isDirectory(Path.of("shared")) ? Path.of("shared") : Path.of(".");
        }

        // 1. tell RMI which address to put into the stubs it hands out
        Net.configureRmi(host);

        Telemetry telemetry = new Telemetry();
        MapData map = MapBuilder.build(dir);
        GameWorld world = new GameWorld(map, kills, minutes * 60);
        String info = "RMI Arena @ " + host + " | map: " + map.sourceDir();

        arena = new ArenaServiceImpl(world, telemetry, info);
        files = new FileServiceImpl(dir, telemetry);

        // 2. export the remote objects (both share one fixed TCP port)
        ArenaService arenaStub = (ArenaService) UnicastRemoteObject.exportObject(arena, SERVER_OBJECT_PORT);
        FileService filesStub = (FileService) UnicastRemoteObject.exportObject(files, SERVER_OBJECT_PORT);

        // 3. start a registry in this JVM and bind the stubs under well-known names
        registry = LocateRegistry.createRegistry(REGISTRY_PORT);
        registry.rebind(ArenaService.NAME, arenaStub);
        registry.rebind(FileService.NAME, filesStub);

        printBanner(host, map, candidates);
        telemetry.log("registry up on %s:%d, bound %s", host, REGISTRY_PORT, List.of(registry.list()));

        Consumer<Frame> spectator = headless ? f -> {} : ServerDashboard.open(host, map, world, arena, telemetry);

        // 4. the game loop: simulate, then push the result to every client
        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "game-loop"));
        loop.scheduleAtFixedRate(() -> {
            try {
                GameWorld.TickResult result = world.tick();
                arena.broadcast(result);
                spectator.accept(new Frame(result.snapshot(), result.events()));
            } catch (Throwable t) {
                // an exception would silently cancel the scheduled task - never let it escape
                t.printStackTrace();
            }
        }, 0, 1_000_000_000L / TICK_RATE, TimeUnit.NANOSECONDS);

        loop.scheduleAtFixedRate(() -> {
            try {
                telemetry.sample();
                arena.reapIdle();
                telemetry.frameBytes(serializedSize(new Frame(world.latest(), List.of())));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static int serializedSize(Object o) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
            out.flush();
            return bytes.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void printBanner(String host, MapData map, List<Net.Candidate> candidates) {
        String line = "=".repeat(72);
        System.out.println(line);
        System.out.println("  RMI ARENA server is running");
        System.out.println();
        System.out.println("  Registry       rmi://" + host + ":" + REGISTRY_PORT);
        System.out.println("  Bound names    " + ArenaService.NAME + ", " + FileService.NAME);
        System.out.println("  Object port    " + SERVER_OBJECT_PORT);
        System.out.println("  Map / shared   " + map.sourceDir() + "  (" + map.obstacles().size() + " obstacles)");
        System.out.println();
        System.out.println("  Friends join with:   java -jar rmi-arena.jar client " + host + " <name>");
        System.out.println("  Remote ls:           java -jar rmi-arena.jar ls " + host);
        System.out.println();
        System.out.println("  All addresses of this machine (restart with --host <ip> if friends can't connect):");
        for (Net.Candidate c : candidates) {
            System.out.println("     " + c.describe() + (c.ip().equals(host) ? "   <- advertised" : ""));
        }
        System.out.println(line);
    }
}

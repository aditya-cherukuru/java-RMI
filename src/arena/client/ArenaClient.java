package arena.client;

import arena.common.ArenaListener;
import arena.common.ArenaService;
import arena.common.FileService;
import arena.common.Frame;
import arena.common.JoinResult;
import arena.common.Net;
import arena.common.PlayerInput;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

import static arena.common.GameRules.REGISTRY_PORT;
import static arena.common.GameRules.TICK_RATE;

/**
 * java -jar rmi-arena.jar client [server-ip] [name] [--pull]
 */
public final class ArenaClient {

    private static final long PUSH_TIMEOUT_NANOS = 2_500_000_000L;

    private final String host;
    private final String name;
    private final String localIp;
    private final ArenaService arena;
    private final FileService files;
    private final Sfx sfx = new Sfx();

    private long token;
    private ArenaPanel panel;
    private ArenaListener listenerHolder;
    private String callbackEndpoint = "-";
    private volatile boolean running = true;
    private volatile boolean pullMode;
    private volatile long lastFrameNanos;
    private volatile long lastTick;

    private final boolean[] keys = new boolean[6];
    private volatile PlayerInput input = PlayerInput.NONE;
    private final LinkedBlockingQueue<Boolean> wake = new LinkedBlockingQueue<>();
    private long lastTaunt;

    private final AtomicLong frames = new AtomicLong(), inputs = new AtomicLong();
    private volatile long framesPerSec, inputsPerSec;
    private volatile double rttMillis = -1;
    private volatile int failures;

    private ArenaClient(String host, String name, String localIp, ArenaService arena, FileService files) {
        this.host = host;
        this.name = name;
        this.localIp = localIp;
        this.arena = arena;
        this.files = files;
    }

    public static void main(String[] args) throws Exception {
        String host = null, name = null;
        boolean pull = false;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--pull")) pull = true;
            else positional.add(a);
        }
        if (positional.size() > 0) host = positional.get(0);
        if (positional.size() > 1) name = positional.get(1);

        Preferences prefs = Preferences.userNodeForPackage(ArenaClient.class);
        if (host == null || name == null) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("usage: client <server-ip> <name> [--pull]");
                System.exit(2);
            }
            JTextField hostField = new JTextField(host != null ? host : prefs.get("host", "localhost"));
            JTextField nameField = new JTextField(name != null ? name : prefs.get("name", System.getProperty("user.name", "player")));
            JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
            form.add(new javax.swing.JLabel("Server IP (shown in the server window):"));
            form.add(hostField);
            form.add(new javax.swing.JLabel("Your name:"));
            form.add(nameField);
            int ok = JOptionPane.showConfirmDialog(null, form, "Join RMI Arena", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) System.exit(0);
            host = hostField.getText().strip();
            name = nameField.getText().strip();
        }
        prefs.put("host", host);
        prefs.put("name", name);

        // 1. Which of my addresses faces the server? The server must call me back on exactly that one.
        String localIp;
        try {
            localIp = Net.localAddressTowards(host, REGISTRY_PORT);
        } catch (IOException e) {
            fail("Cannot reach " + host + ":" + REGISTRY_PORT + "  (" + e.getMessage() + ")\n\n"
                    + "- Is the server running?  Is the IP right?\n"
                    + "- Are both laptops on the same network? Campus Wi-Fi (eduroam) often blocks\n"
                    + "  laptop-to-laptop traffic: use a phone hotspot or Tailscale instead.\n"
                    + "- Windows firewall on the server: allow Java on private AND public networks.");
            return;
        }
        Net.configureRmi(localIp);

        // 2. Classic RMI: ask the registry for the stubs
        Registry registry = LocateRegistry.getRegistry(host, REGISTRY_PORT);
        ArenaService arena;
        FileService files;
        try {
            arena = (ArenaService) registry.lookup(ArenaService.NAME);
            files = (FileService) registry.lookup(FileService.NAME);
        } catch (Exception e) {
            fail("Registry at " + host + ":" + REGISTRY_PORT + " answered, but lookup failed:\n" + Net.rootCause(e));
            return;
        }

        ArenaClient client = new ArenaClient(host, name, localIp, arena, files);
        client.start(pull);
    }

    private void start(boolean forcePull) throws Exception {
        CallbackListener listener = new CallbackListener();
        ArenaListener stub = null;
        if (!forcePull) {
            // 3. Export our own remote object: from now on the server can call US
            stub = (ArenaListener) Net.exportCallback(listener);
            callbackEndpoint = Net.endpointOf(stub);
            listenerHolder = listener;
        }

        JoinResult joined;
        try {
            joined = arena.join(name, stub);
        } catch (RemoteException e) {
            String advertised = Net.endpointOf(arena);
            fail("join() failed: " + Net.rootCause(e) + "\n\n"
                    + "The server's stub points to " + advertised + ".\n"
                    + "If that is not the IP you typed (" + host + "), restart the server with\n"
                    + "   --host " + host);
            return;
        }
        token = joined.token();
        pullMode = forcePull;
        lastFrameNanos = System.nanoTime();

        SwingUtilities.invokeAndWait(() -> buildWindow(joined));
        listener.ready(panel);

        daemon("input-sender", this::sendLoop);
        daemon("pinger", this::pingLoop);
        daemon("watchdog", this::watchLoop);
    }

    // ------------------------------------------------------------------ the callback

    /** Our remote object. The server invokes onFrame() ~30 times per second. */
    private final class CallbackListener implements ArenaListener {
        private final List<Frame> early = new ArrayList<>();
        private ArenaPanel target;

        synchronized void ready(ArenaPanel p) {
            target = p;
            early.forEach(p::acceptFrame);
            early.clear();
        }

        @Override
        public void onFrame(Frame frame) {
            if (pullMode) return;
            synchronized (this) {
                if (target == null) {
                    early.add(frame);
                    return;
                }
            }
            received(frame);
        }
    }

    private void received(Frame frame) {
        lastFrameNanos = System.nanoTime();
        lastTick = frame.snapshot().tick();
        frames.incrementAndGet();
        panel.acceptFrame(frame);
    }

    // ------------------------------------------------------------------ background loops

    private void sendLoop() {
        while (running) {
            try {
                wake.poll(100, TimeUnit.MILLISECONDS);   // send on key change, or every 100 ms as heartbeat
                wake.clear();
                arena.sendInput(token, input);
                inputs.incrementAndGet();
                if (failures > 0) panel.setStatus(null);
                failures = 0;
            } catch (InterruptedException e) {
                return;
            } catch (RemoteException e) {
                if (++failures >= 5) panel.setStatus("Lost connection: " + Net.rootCause(e));
                sleep(300);
            }
        }
    }

    private void pingLoop() {
        long lastFrames = 0, lastInputs = 0;
        while (running) {
            sleep(1000);
            long f = frames.get(), i = inputs.get();
            framesPerSec = f - lastFrames;
            inputsPerSec = i - lastInputs;
            lastFrames = f;
            lastInputs = i;
            try {
                long t0 = System.nanoTime();
                arena.ping(token, t0);
                double ms = (System.nanoTime() - t0) / 1e6;
                rttMillis = rttMillis < 0 ? ms : rttMillis * 0.7 + ms * 0.3;
            } catch (RemoteException ignored) {
                // the sender loop reports connection problems
            }
        }
    }

    /** Push is the default. If no callback arrives (firewall on this laptop?), fall back to polling. */
    private void watchLoop() {
        while (running) {
            if (!pullMode) {
                sleep(250);
                if (System.nanoTime() - lastFrameNanos > PUSH_TIMEOUT_NANOS) {
                    pullMode = true;
                    panel.localMessage("No callbacks arriving (firewall?) - switched to PULL: polling the server");
                }
                continue;
            }
            long t0 = System.nanoTime();
            try {
                received(arena.poll(token));
            } catch (RemoteException e) {
                sleep(200);
            }
            long spent = (System.nanoTime() - t0) / 1_000_000;
            sleep(Math.max(1, 1000 / TICK_RATE - spent));
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildWindow(JoinResult joined) {
        panel = new ArenaPanel(joined.map(), joined.playerId(), sfx);
        FileBrowser browser = new FileBrowser(files, host);
        panel.setFileBrowser(browser);
        panel.setHudLines(this::hud);

        JFrame frame = new JFrame("RMI Arena  -  " + name + " @ " + host);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit(frame);
            }
        });
        panel.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                java.util.Arrays.fill(keys, false);
                updateInput();
            }
        });
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (browser.visible) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_UP, KeyEvent.VK_W -> browser.up();
                        case KeyEvent.VK_DOWN, KeyEvent.VK_S -> browser.down();
                        case KeyEvent.VK_ENTER, KeyEvent.VK_RIGHT -> browser.open();
                        case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_LEFT -> browser.parent();
                        case KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE -> browser.close();
                        default -> { }
                    }
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_TAB -> {
                        java.util.Arrays.fill(keys, false);
                        updateInput();
                        browser.toggle();
                    }
                    case KeyEvent.VK_F3 -> panel.toggleHud();
                    case KeyEvent.VK_M -> sfx.toggleMute();
                    case KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 -> taunt(e.getKeyCode() - KeyEvent.VK_1);
                    default -> setKey(e.getKeyCode(), true);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                setKey(e.getKeyCode(), false);
            }
        });
        frame.setVisible(true);
        panel.requestFocusInWindow();
    }

    private void setKey(int code, boolean down) {
        int k = switch (code) {
            case KeyEvent.VK_W, KeyEvent.VK_UP -> 0;
            case KeyEvent.VK_S, KeyEvent.VK_DOWN -> 1;
            case KeyEvent.VK_A, KeyEvent.VK_LEFT -> 2;
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> 3;
            case KeyEvent.VK_SPACE -> 4;
            case KeyEvent.VK_SHIFT -> 5;
            default -> -1;
        };
        if (k < 0 || keys[k] == down) return;
        keys[k] = down;
        updateInput();
    }

    private void updateInput() {
        input = new PlayerInput(keys[0], keys[1], keys[2], keys[3], keys[4], keys[5]);
        wake.offer(Boolean.TRUE);
    }

    private void taunt(int index) {
        long now = System.currentTimeMillis();
        if (now - lastTaunt < 1500) return;
        lastTaunt = now;
        daemon("taunt", () -> {
            try {
                arena.taunt(token, index);
            } catch (RemoteException ignored) {
                // cosmetic
            }
        });
    }

    private List<String> hud() {
        List<String> l = new ArrayList<>();
        l.add("RMI TELEMETRY  (F3 to hide)");
        l.add("server     rmi://" + host + ":" + REGISTRY_PORT + "  -> " + Net.endpointOf(arena));
        l.add(pullMode
                ? "mode       PULL   client -> ArenaService.poll()"
                : "mode       PUSH   server -> ArenaListener.onFrame()");
        l.add("callback   " + (listenerHolder == null ? "not exported (--pull)" : callbackEndpoint + "  (my exported stub)"));
        l.add(String.format("frames/s   %d   (tick #%d)", framesPerSec, lastTick));
        l.add(String.format("ping RTT   %s", rttMillis < 0 ? "-" : String.format("%.1f ms", rttMillis)));
        l.add("inputs/s   " + inputsPerSec + "   sendInput() incl. heartbeat");
        l.add("local ip   " + localIp + (sfx.muted() ? "    [muted]" : ""));
        return l;
    }

    private void quit(JFrame frame) {
        running = false;
        frame.dispose();
        Thread bye = new Thread(() -> {
            try {
                arena.leave(token);
            } catch (RemoteException ignored) {
                // server gone anyway
            }
            if (listenerHolder != null) {
                try {
                    UnicastRemoteObject.unexportObject(listenerHolder, true);
                } catch (RemoteException ignored) {
                    // exiting
                }
            }
            System.exit(0);
        });
        bye.start();
    }

    // ------------------------------------------------------------------ helpers

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "RMI Arena", JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }
}

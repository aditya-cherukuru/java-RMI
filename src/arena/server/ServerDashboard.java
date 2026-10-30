package arena.server;

import arena.bot.Bot;
import arena.client.ArenaPanel;
import arena.common.ArenaService;
import arena.common.FileService;
import arena.common.Frame;
import arena.common.MapData;
import arena.common.Net;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static arena.common.GameRules.REGISTRY_PORT;
import static arena.common.GameRules.SERVER_OBJECT_PORT;

/** The server window: what the projector shows during the demo. */
final class ServerDashboard {

    private static final Color BG = new Color(0x0B1020);
    private static final Color FG = new Color(0xD8DEF0);
    private static final Color ACCENT = new Color(0x78E1FF);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final String host;
    private final MapData map;
    private final GameWorld world;
    private final ArenaServiceImpl arena;
    private final Telemetry telemetry;
    private final List<Bot> bots = new CopyOnWriteArrayList<>();
    private int botCounter;

    private DefaultTableModel sessionsModel;
    private JTextArea rates;
    private JTextArea log;
    private int shownLogLines;
    private String lastLogLine = "";

    private ServerDashboard(String host, MapData map, GameWorld world, ArenaServiceImpl arena, Telemetry telemetry) {
        this.host = host;
        this.map = map;
        this.world = world;
        this.arena = arena;
        this.telemetry = telemetry;
    }

    /** Opens the window and returns where the game loop should send each frame. */
    static Consumer<Frame> open(String host, MapData map, GameWorld world, ArenaServiceImpl arena,
                                Telemetry telemetry) throws Exception {
        AtomicReference<ArenaPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                panel.set(new ServerDashboard(host, map, world, arena, telemetry).build()));
        return panel.get()::acceptFrame;
    }

    private ArenaPanel build() {
        ArenaPanel spectator = new ArenaPanel(map, -1, null);
        spectator.setHudLines(() -> List.of(
                "SERVER VIEW  (spectator, no RMI involved)",
                "join:  java -jar rmi-arena.jar client " + host + " <name>",
                "ls:    java -jar rmi-arena.jar ls " + host,
                "bots:  java -jar rmi-arena.jar bot " + host + " 2"));

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(BG);
        side.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        side.setPreferredSize(new Dimension(540, 800));

        JLabel title = new JLabel("RMI CONTROL ROOM");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        title.setForeground(ACCENT);
        side.add(left(title));
        JLabel info = new JLabel("<html><body style='color:#D8DEF0'>"
                + "registry <b>rmi://" + host + ":" + REGISTRY_PORT + "</b><br>"
                + "bound: <b>" + ArenaService.NAME + "</b>, <b>" + FileService.NAME + "</b>"
                + " &nbsp;(objects on port " + SERVER_OBJECT_PORT + ")<br>"
                + "map built from: " + map.sourceDir() + "</body></html>");
        info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        side.add(left(info));
        side.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(button("Restart round", () -> {
            world.restartRound();
            telemetry.log("round restarted from the dashboard");
        }));
        buttons.add(button("+ Bot", this::addBot));
        buttons.add(button("Remove bots", this::removeBots));
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        side.add(left(buttons));
        side.add(Box.createVerticalStrut(8));

        side.add(left(heading("Connected clients")));
        sessionsModel = new DefaultTableModel(
                new String[]{"Player", "Client IP", "Mode", "Callback", "Pushed", "Polls", "Inputs"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable table = new JTable(sessionsModel);
        table.setFont(MONO);
        table.setRowHeight(20);
        table.setBackground(new Color(0x111830));
        table.setForeground(FG);
        table.setGridColor(new Color(0x1E2847));
        table.getTableHeader().setBackground(new Color(0x1E2847));
        table.getTableHeader().setForeground(ACCENT);
        int[] widths = {95, 115, 45, 70, 60, 50, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JScrollPane tableScroll = dark(new JScrollPane(table));
        tableScroll.setPreferredSize(new Dimension(500, 150));
        tableScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        side.add(left(tableScroll));
        side.add(Box.createVerticalStrut(8));

        side.add(left(heading("Remote calls per second")));
        rates = textArea();
        rates.setRows(9);
        JScrollPane ratesScroll = dark(new JScrollPane(rates));
        ratesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 175));
        side.add(left(ratesScroll));
        side.add(Box.createVerticalStrut(8));

        side.add(left(heading("Call log")));
        log = textArea();
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        side.add(left(dark(new JScrollPane(log))));

        JFrame frame = new JFrame("RMI Arena  -  Server Control Room  -  " + host);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(spectator, BorderLayout.CENTER);
        frame.add(side, BorderLayout.EAST);
        frame.getContentPane().setBackground(BG);
        frame.setSize(1560, 860);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Timer(500, e -> refresh()).start();
        return spectator;
    }

    private void refresh() {
        sessionsModel.setRowCount(0);
        for (Session s : arena.sessions()) {
            sessionsModel.addRow(new Object[]{
                    s.name, s.host, s.isPush() ? "PUSH" : "PULL",
                    s.isPush() ? String.format("%.1f ms", s.callbackMillis()) : "-",
                    s.framesPushed.sum(), s.polls.sum(), s.inputs.sum()});
        }

        StringBuilder sb = new StringBuilder();
        long callbacksPerSec = 0;
        for (Map.Entry<String, Long> e : telemetry.rates().entrySet()) {
            sb.append(String.format("%-22s %5d /s   total %d%n", e.getKey(), e.getValue(), telemetry.total(e.getKey())));
            if (e.getKey().startsWith("onFrame")) callbacksPerSec = e.getValue();
        }
        int bytes = telemetry.frameBytes();
        sb.append(String.format("%none frame serialized   ~%d bytes%n", bytes));
        sb.append(String.format("push bandwidth         ~%.1f KB/s (all clients)%n", bytes * callbacksPerSec / 1024.0));
        rates.setText(sb.toString());

        List<String> lines = telemetry.logLines();
        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        if (lines.size() != shownLogLines || !last.equals(lastLogLine)) {
            log.setText(String.join("\n", lines));
            log.setCaretPosition(log.getDocument().getLength());
            shownLogLines = lines.size();
            lastLogLine = last;
        }
    }

    private void addBot() {
        String name = Bot.randomName(botCounter++);
        Thread t = new Thread(() -> {
            try {
                bots.add(Bot.start(host, name));
            } catch (Exception ex) {
                telemetry.log("could not start bot: %s", Net.rootCause(ex));
            }
        }, "bot-launcher");
        t.setDaemon(true);
        t.start();
    }

    private void removeBots() {
        List<Bot> all = new ArrayList<>(bots);
        bots.clear();
        Thread t = new Thread(() -> all.forEach(Bot::stop), "bot-stopper");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ widgets

    private static JComponent left(JComponent c) {
        c.setAlignmentX(0f);
        return c;
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        l.setForeground(new Color(0x8A93B0));
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return l;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JTextArea textArea() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setFont(MONO);
        t.setBackground(new Color(0x070A12));
        t.setForeground(FG);
        t.setCaretColor(FG);
        t.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return t;
    }

    private static JScrollPane dark(JScrollPane s) {
        s.getViewport().setBackground(new Color(0x070A12));
        s.setBorder(BorderFactory.createLineBorder(new Color(0x1E2847)));
        return s;
    }
}

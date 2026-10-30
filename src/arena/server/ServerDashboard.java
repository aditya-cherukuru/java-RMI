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

    private static final Color BG = new Color(0x06090F);
    private static final Color PANEL = new Color(0x0B1020);
    private static final Color FG = new Color(0xD8DEF0);
    private static final Color ACCENT = new Color(0x78E1FF);
    private static final Color GREEN = new Color(0x7CFF4F);
    private static final Color GOLD = new Color(0xFFE066);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final Font HEADING_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

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
                "SERVER VIEW  —  spectator (no RMI involved)",
                "join:   java -jar rmi-arena.jar client " + host + " <YourName>",
                "ls:     java -jar rmi-arena.jar ls " + host,
                "bots:   java -jar rmi-arena.jar bot " + host + " 2"));

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(BG);
        side.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        side.setPreferredSize(new Dimension(560, 860));

        // Title bar
        JLabel title = new JLabel("\u26A1  RMI ARENA  —  CONTROL ROOM");
        title.setFont(TITLE_FONT);
        title.setForeground(ACCENT);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        side.add(left(title));

        // Registry info panel
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBackground(new Color(0x0D1528));
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x1E3060), 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        infoPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        addInfoRow(infoPanel, "Registry", "rmi://" + host + ":" + REGISTRY_PORT, ACCENT);
        addInfoRow(infoPanel, "Services", ArenaService.NAME + "  ●  " + FileService.NAME, GREEN);
        addInfoRow(infoPanel, "Objects", "TCP port " + SERVER_OBJECT_PORT + "  (fixed — easy firewall rule)", FG);
        addInfoRow(infoPanel, "Map", map.sourceDir().toString(), GOLD);
        side.add(left(infoPanel));
        side.add(Box.createVerticalStrut(10));

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(button("↺ Restart round", () -> {
            world.restartRound();
            telemetry.log("round restarted from the dashboard");
        }));
        buttons.add(button("🤖 + Bot", this::addBot));
        buttons.add(button("🗑 Remove bots", this::removeBots));
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        side.add(left(buttons));
        side.add(Box.createVerticalStrut(10));

        // Connected clients table
        side.add(left(heading("CONNECTED CLIENTS")));
        sessionsModel = new DefaultTableModel(
                new String[]{"Player", "Client IP", "Mode", "Ping", "Pushed", "Polls", "Inputs"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(sessionsModel) {
            @Override
            public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer r, int row, int col) {
                java.awt.Component c = super.prepareRenderer(r, row, col);
                String mode = (String) getModel().getValueAt(row, 2);
                if (col == 2) {
                    c.setForeground("PUSH".equals(mode) ? GREEN : GOLD);
                } else {
                    c.setForeground(FG);
                }
                c.setBackground(row % 2 == 0 ? new Color(0x0E1830) : new Color(0x111A38));
                return c;
            }
        };
        table.setFont(MONO);
        table.setRowHeight(22);
        table.setBackground(new Color(0x0E1830));
        table.setForeground(FG);
        table.setGridColor(new Color(0x1A2545));
        table.setShowGrid(true);
        table.getTableHeader().setBackground(new Color(0x162040));
        table.getTableHeader().setForeground(ACCENT);
        table.getTableHeader().setFont(MONO_BOLD);
        int[] widths = {100, 120, 50, 70, 60, 50, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JScrollPane tableScroll = dark(new JScrollPane(table));
        tableScroll.setPreferredSize(new Dimension(530, 160));
        tableScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        side.add(left(tableScroll));
        side.add(Box.createVerticalStrut(10));

        // Remote calls per second
        side.add(left(heading("REMOTE CALLS PER SECOND")));
        rates = textArea();
        rates.setRows(10);
        JScrollPane ratesScroll = dark(new JScrollPane(rates));
        ratesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        side.add(left(ratesScroll));
        side.add(Box.createVerticalStrut(10));

        // Call log
        side.add(left(heading("LIVE CALL LOG")));
        log = textArea();
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        side.add(left(dark(new JScrollPane(log))));

        // Main window
        JFrame frame = new JFrame("RMI Arena  —  Server  —  " + host);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(spectator, BorderLayout.CENTER);
        frame.add(side, BorderLayout.EAST);
        frame.getContentPane().setBackground(BG);
        frame.setSize(1600, 900);
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
            sb.append(String.format("  %-22s %5d /s   (total: %,d)%n",
                    e.getKey(), e.getValue(), telemetry.total(e.getKey())));
            if (e.getKey().startsWith("onFrame")) callbacksPerSec = e.getValue();
        }
        int bytes = telemetry.frameBytes();
        sb.append(String.format("%n  one frame serialized   ~%,d bytes%n", bytes));
        sb.append(String.format("  push bandwidth         ~%.1f KB/s (all clients)%n",
                bytes * callbacksPerSec / 1024.0));
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

    private static void addInfoRow(JPanel panel, String label, String value, Color valueColor) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
        row.setOpaque(false);
        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        lbl.setForeground(new Color(0x8A93B0));
        JLabel val = new JLabel(value);
        val.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        val.setForeground(valueColor);
        row.add(lbl);
        row.add(val);
        panel.add(row);
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(0f);
        return c;
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HEADING_FONT);
        l.setForeground(new Color(0x8A93B0));
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
        return l;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        b.setBackground(new Color(0x162040));
        b.setForeground(new Color(0xD8DEF0));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x2A4080), 1),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        b.setOpaque(true);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JTextArea textArea() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setFont(MONO);
        t.setBackground(new Color(0x060810));
        t.setForeground(FG);
        t.setCaretColor(FG);
        t.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return t;
    }

    private static JScrollPane dark(JScrollPane s) {
        s.getViewport().setBackground(new Color(0x060810));
        s.setBorder(BorderFactory.createLineBorder(new Color(0x1A2545)));
        return s;
    }
}

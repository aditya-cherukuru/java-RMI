package arena.common;

/** Constants shared by server, client and bots. */
public final class GameRules {
    private GameRules() {}

    public static final int REGISTRY_PORT = 1099;
    /** Port the server exports its remote objects on (fixed so firewalls are easy). */
    public static final int SERVER_OBJECT_PORT = 1100;
    /** Port a client exports its callback listener on (falls back to any free port). */
    public static final int CLIENT_CALLBACK_PORT = 1101;

    public static final int TICK_RATE = 30;
    public static final int WORLD_W = 1280;
    public static final int WORLD_H = 720;

    public static final double SHIP_RADIUS = 16;
    public static final int MAX_HP = 100;
    public static final int MAX_PLAYERS = 8;

    public static final String[] TAUNTS = {
            "gg ez",
            "java.rmi.RemoteException: skill not found",
            "nice ping lol",
            "it works on my machine",
    };

    public static final int[] PLAYER_COLORS = {
            0xFF3B6B, 0x3BD1FF, 0x7CFF4F, 0xFFC93B,
            0xC77DFF, 0xFF8A3B, 0x3BFFC2, 0xF5F5F5,
    };
}

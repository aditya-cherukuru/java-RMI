package arena.server;

import arena.common.ArenaListener;
import arena.common.ArenaService;
import arena.common.Frame;
import arena.common.JoinResult;
import arena.common.Net;
import arena.common.PlayerInput;

import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The remote object behind the "ArenaService" registry name. */
final class ArenaServiceImpl implements ArenaService {
    private static final long TIMEOUT_MILLIS = 8000;

    private final GameWorld world;
    private final Telemetry telemetry;
    private final String serverInfo;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    ArenaServiceImpl(GameWorld world, Telemetry telemetry, String serverInfo) {
        this.world = world;
        this.telemetry = telemetry;
        this.serverInfo = serverInfo;
    }

    @Override
    public JoinResult join(String playerName, ArenaListener listener) throws RemoteException {
        telemetry.call("join");
        String host = clientHost();
        GameWorld.Player p;
        try {
            p = world.addPlayer(playerName);
        } catch (IllegalStateException full) {
            telemetry.log("join(\"%s\") from %s -> rejected: %s", playerName, host, full.getMessage());
            throw new RemoteException(full.getMessage());
        }
        long token = random.nextLong();
        sessions.put(token, new Session(token, p.id, p.name, host, listener, telemetry));
        telemetry.log("join(\"%s\") from %s -> player #%d, %s", p.name, host, p.id,
                listener != null ? "PUSH mode (callback stub -> " + Net.endpointOf(listener) + ")" : "PULL mode");
        return new JoinResult(token, p.id, p.color, world.map(), serverInfo);
    }

    @Override
    public void sendInput(long token, PlayerInput input) throws RemoteException {
        telemetry.call("sendInput");
        Session s = session(token);
        s.touch();
        s.inputs.increment();
        world.setInput(s.playerId, input);
    }

    @Override
    public Frame poll(long token) throws RemoteException {
        telemetry.call("poll");
        Session s = session(token);
        s.touch();
        return s.poll(world.latest());
    }

    @Override
    public long ping(long token, long clientNanos) throws RemoteException {
        telemetry.call("ping");
        session(token).touch();
        return clientNanos;
    }

    @Override
    public void taunt(long token, int index) throws RemoteException {
        telemetry.call("taunt");
        Session s = session(token);
        s.touch();
        world.taunt(s.playerId, index);
    }

    @Override
    public void leave(long token) throws RemoteException {
        telemetry.call("leave");
        Session s = sessions.remove(token);
        if (s != null) {
            world.removePlayer(s.playerId, "left the arena");
            s.close();
            telemetry.log("leave() from %s (%s)", s.name, s.host);
        }
    }

    // ------------------------------------------------------------------ server side

    /** Fan the tick result out to every client's push channel. */
    void broadcast(GameWorld.TickResult result) {
        for (Session s : sessions.values()) {
            s.addEvents(result.events());
            s.offer(result.snapshot());
        }
    }

    /** Drop clients that went silent (laptop closed, Wi-Fi gone, process killed). */
    void reapIdle() {
        for (Session s : sessions.values()) {
            if (s.idleMillis() > TIMEOUT_MILLIS && sessions.remove(s.token) != null) {
                world.removePlayer(s.playerId, "timed out (no heartbeat for " + TIMEOUT_MILLIS / 1000 + "s)");
                s.close();
                telemetry.log("%s @ %s timed out - removed", s.name, s.host);
            }
        }
    }

    Collection<Session> sessions() {
        return sessions.values();
    }

    private Session session(long token) throws RemoteException {
        Session s = sessions.get(token);
        if (s == null) throw new RemoteException("Unknown session - you were removed (timeout?) - please rejoin");
        return s;
    }

    /** The IP of the machine that made the current remote call. */
    static String clientHost() {
        try {
            return RemoteServer.getClientHost();
        } catch (ServerNotActiveException e) {
            return "local";
        }
    }
}

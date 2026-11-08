package arena.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The game server's remote interface (client -> server calls).
 *
 * Every call except join() carries the secret token handed out by join(),
 * so one client cannot steer another client's ship.
 */
public interface ArenaService extends Remote {
    String NAME = "ArenaService";

    /**
     * Join the arena. The listener is a remote object living on the client;
     * the server calls it back to push frames (event-based communication).
     * Pass null to use pull mode (poll) only.
     */
    JoinResult join(String playerName, ArenaListener listener) throws RemoteException;

    /** Current key state. Also acts as heartbeat. */
    void sendInput(long token, PlayerInput input) throws RemoteException;

    /** Pull-mode fallback: fetch the latest frame plus queued events. */
    Frame poll(long token) throws RemoteException;

    /** Round-trip probe. Returns the argument unchanged. */
    long ping(long token, long clientNanos) throws RemoteException;

    /** Broadcast one of the canned tactical messages in {@link GameRules#TAUNTS}. */
    void taunt(long token, int index) throws RemoteException;

    void leave(long token) throws RemoteException;
}

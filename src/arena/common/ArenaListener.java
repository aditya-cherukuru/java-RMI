package arena.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Callback interface implemented and exported by each client.
 * The client passes its stub to {@link ArenaService#join}; from then on the
 * server is the caller and the client is the callee.
 */
public interface ArenaListener extends Remote {
    void onFrame(Frame frame) throws RemoteException;
}

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Step 1 - the remote interface (the "contract").
 * Shared by client and server. Every remote method must declare RemoteException.
 */
public interface DirectoryService extends Remote {
    List<String> listFiles(String path) throws RemoteException;
}

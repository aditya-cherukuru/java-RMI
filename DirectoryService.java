import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface DirectoryService extends Remote {
    List<String> listFiles() throws RemoteException;
}
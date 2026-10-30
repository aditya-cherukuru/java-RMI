package arena.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** Remote "ls": lists a directory inside the server's shared folder. */
public interface FileService extends Remote {
    String NAME = "FileService";

    /** @param relativePath path relative to the shared root ("" or "." for the root) */
    List<FileEntry> list(String relativePath) throws RemoteException;

    /** Absolute path of the shared root on the server, for display. */
    String rootName() throws RemoteException;
}

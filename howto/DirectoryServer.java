import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.stream.Stream;

/**
 * Step 2 - the server: implement, export, register.
 *
 *   java DirectoryServer <this-machine-ip>
 */
public class DirectoryServer {
    public static void main(String[] args) throws Exception {
        // The IP that goes into the stub - must be reachable from the client machine
        if (args.length > 0) {
            System.setProperty("java.rmi.server.hostname", args[0]);
        }

        DirectoryService service = path -> {
            try (Stream<Path> entries = Files.list(Path.of(path))) {
                return entries
                        .map(p -> Files.isDirectory(p)
                                ? p.getFileName() + "/"
                                : p.getFileName().toString())
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new RemoteException("Could not read " + path, e);
            }
        };

        // Export: start listening on port 5000 and create the stub (a dynamic proxy)
        DirectoryService stub =
                (DirectoryService) UnicastRemoteObject.exportObject(service, 5000);

        // Register the stub under a name in the RMI registry (port 1099)
        Registry registry = LocateRegistry.createRegistry(1099);
        registry.rebind("DirectoryService", stub);

        System.out.println("DirectoryService ready on " +
                System.getProperty("java.rmi.server.hostname", "localhost") + ":1099");
    }
}

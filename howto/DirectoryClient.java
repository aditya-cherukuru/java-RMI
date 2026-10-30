import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Step 3 - the client: look up the stub, call it like a local object.
 *
 *   java DirectoryClient <server-ip> [path]
 */
public class DirectoryClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        String path = args.length > 1 ? args[1] : ".";

        Registry registry = LocateRegistry.getRegistry(host, 1099);
        DirectoryService service = (DirectoryService) registry.lookup("DirectoryService");

        System.out.println("Contents of " + path + " on " + host + ":");
        for (String file : service.listFiles(path)) {   // <- this is the remote call
            System.out.println("  " + file);
        }
    }
}

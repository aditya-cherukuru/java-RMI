import java.io.IOException;
import java.nio.file.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.util.List;
import java.util.stream.Stream;

public class DirectoryServer {
    public static void main(String[] args) throws Exception {
        System.setProperty(
                "java.rmi.server.hostname",
                "100.81.206.180");
        DirectoryService service = () -> {
            try (Stream<Path> paths = Files.list(Path.of("."))) {
                return paths
                        .map(p -> p.getFileName().toString())
                        .toList();
            } catch (IOException e) {
                throw new RemoteException(
                        "Could not read directory", e);
            }
        };

        DirectoryService stub = (DirectoryService) UnicastRemoteObject
                .exportObject(service, 5000);

        Registry registry = LocateRegistry.createRegistry(1099);

        registry.rebind("DirectoryService", stub);

        System.out.println("Directory service is ready.");
    }
}

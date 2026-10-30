import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class DirectoryClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0
            ? args[0] : "localhost";

        Registry registry =
            LocateRegistry.getRegistry("192.168.1.10", 1099);

        DirectoryService service =
            (DirectoryService) registry.lookup(
                "DirectoryService");

        System.out.println("Remote directory contents:");

        for (String file : service.listFiles()) {
            System.out.println(file);
        }
    }
}
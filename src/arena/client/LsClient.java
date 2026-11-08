package arena.client;

import arena.common.FileEntry;
import arena.common.FileService;
import arena.common.Net;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static arena.common.GameRules.REGISTRY_PORT;

/**
 * Remote directory listing CLI:  java -jar rmi-arena.jar ls <server-ip> [path]
 */
public final class LsClient {
    private LsClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ls <server-ip> [path]");
            System.exit(2);
        }
        String host = args[0];
        String path = args.length > 1 ? args[1] : "";
        Net.configureRmi(null);

        Registry registry = LocateRegistry.getRegistry(host, REGISTRY_PORT);     // 1. find the registry
        FileService files = (FileService) registry.lookup(FileService.NAME);   // 2. get the stub

        long t0 = System.nanoTime();
        List<FileEntry> entries;
        try {
            entries = files.list(path);                                        // 3. remote call
        } catch (RemoteException e) {
            System.err.println("ls failed: " + Net.rootCause(e));
            System.exit(1);
            return;
        }
        double ms = (System.nanoTime() - t0) / 1e6;

        String root = files.rootName();
        System.out.printf("%s%s  on  rmi://%s:%d   (%d entries, %.1f ms)%n",
                root, path.isEmpty() ? "" : "/" + path, host, REGISTRY_PORT, entries.size(), ms);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (FileEntry e : entries) {
            System.out.printf("%s  %9s  %s  %s%n",
                    e.directory() ? "d" : "-",
                    e.directory() ? "-" : ArenaPanel.human(e.size()),
                    fmt.format(new Date(e.modifiedMillis())),
                    e.name() + (e.directory() ? "/" : ""));
        }
    }
}

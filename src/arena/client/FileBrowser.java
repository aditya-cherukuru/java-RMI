package arena.client;

import arena.common.FileEntry;
import arena.common.FileService;

import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-game remote file browser: press TAB to browse the server's shared
 * folder. Every folder you open is one FileService.list() remote call.
 * Calls run off the Swing thread so a slow network never freezes the game.
 */
public final class FileBrowser {
    private final FileService files;
    private final String host;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-browser");
        t.setDaemon(true);
        return t;
    });

    volatile boolean visible;
    private volatile String path = "";
    private volatile String root = "";
    private volatile List<FileEntry> entries = List.of();
    private volatile int selected;
    private volatile String status = "";

    public FileBrowser(FileService files, String host) {
        this.files = files;
        this.host = host;
    }

    public void toggle() {
        visible = !visible;
        if (visible) load(path);
    }

    public void close() {
        visible = false;
    }

    public void up() {
        selected = Math.max(0, selected - 1);
    }

    public void down() {
        selected = Math.min(entries.size() - 1, selected + 1);
    }

    public void open() {
        List<FileEntry> list = entries;
        if (selected < 0 || selected >= list.size()) return;
        FileEntry e = list.get(selected);
        if (e.directory()) load(path.isEmpty() ? e.name() : path + "/" + e.name());
    }

    public void parent() {
        if (path.isEmpty()) return;
        int i = path.lastIndexOf('/');
        load(i < 0 ? "" : path.substring(0, i));
    }

    private void load(String target) {
        status = "calling FileService.list(\"" + target + "\") ...";
        io.execute(() -> {
            long t0 = System.nanoTime();
            try {
                List<FileEntry> result = files.list(target);
                double ms = (System.nanoTime() - t0) / 1e6;
                if (root.isEmpty()) root = files.rootName();
                path = target;
                entries = result;
                selected = 0;
                status = String.format("%d entries   -   round trip %.1f ms", result.size(), ms);
            } catch (RemoteException e) {
                Throwable c = e;
                while (c.getCause() != null) c = c.getCause();
                status = "RemoteException: " + c.getMessage();
            }
        });
    }

    String displayPath() {
        String r = root.isEmpty() ? "<shared>" : root;
        return path.isEmpty() ? r : r + "/" + path;
    }

    String path() {
        return path;
    }

    String host() {
        return host;
    }

    List<FileEntry> entries() {
        return entries;
    }

    int selected() {
        return selected;
    }

    String status() {
        return status;
    }
}

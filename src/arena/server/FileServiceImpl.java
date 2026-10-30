package arena.server;

import arena.common.FileEntry;
import arena.common.FileService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The seminar HowTo, production edition: remote "ls", jailed to one shared folder. */
final class FileServiceImpl implements FileService {
    private final Path root;
    private final Telemetry telemetry;

    FileServiceImpl(Path root, Telemetry telemetry) {
        this.root = root.toAbsolutePath().normalize();
        this.telemetry = telemetry;
    }

    @Override
    public List<FileEntry> list(String relativePath) throws RemoteException {
        telemetry.call("list");
        String rel = relativePath == null ? "" : relativePath.replace('\\', '/');
        Path dir = root.resolve(rel.startsWith("/") ? rel.substring(1) : rel).normalize();
        String caller = ArenaServiceImpl.clientHost();

        // no "../../" escapes out of the shared folder
        if (!dir.startsWith(root)) {
            telemetry.log("list(\"%s\") from %s -> DENIED (outside shared folder)", relativePath, caller);
            throw new RemoteException("Access denied: " + relativePath + " is outside the shared folder");
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<FileEntry> entries = s.map(FileServiceImpl::entry)
                    .sorted(Comparator.comparing(FileEntry::directory).reversed()
                            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER))
                    .limit(500)
                    .toList();
            telemetry.log("list(\"%s\") from %s -> %d entries", rel.isEmpty() ? "." : rel, caller, entries.size());
            return entries;
        } catch (IOException e) {
            telemetry.log("list(\"%s\") from %s -> %s", relativePath, caller, e);
            throw new RemoteException("Cannot list " + relativePath + ": " + e.getMessage());
        }
    }

    @Override
    public String rootName() {
        telemetry.call("rootName");
        return root.toString();
    }

    private static FileEntry entry(Path p) {
        boolean dir = Files.isDirectory(p);
        long size = 0, modified = 0;
        try {
            size = dir ? 0 : Files.size(p);
            modified = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ignored) {
            // show it anyway, with zeros
        }
        return new FileEntry(p.getFileName().toString(), dir, size, modified);
    }
}

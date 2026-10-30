package arena.common;

import java.io.Serializable;

/** One line of a remote directory listing. Passed by value (serialized). */
public record FileEntry(String name, boolean directory, long size, long modifiedMillis)
        implements Serializable {
}

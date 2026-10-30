package arena.common;

import java.io.Serializable;
import java.util.List;

/**
 * What one callback (or one poll) delivers: the newest snapshot plus every
 * event queued for this client since the previous frame.
 */
public record Frame(GameSnapshot snapshot, List<GameEvent> events) implements Serializable {
}

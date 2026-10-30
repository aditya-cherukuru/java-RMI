package arena.common;

import java.io.Serializable;

/** Something that happened (kill, join, chat...). Never conflated - every event is delivered. */
public record GameEvent(Type type, String text, int colorRgb, double x, double y, int subjectId)
        implements Serializable {

    public enum Type { JOIN, LEAVE, KILL, HIT, CRATE, PICKUP, TAUNT, ROUND, SYSTEM }
}

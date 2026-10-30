package arena.common;

import java.io.Serializable;

public record PlayerInput(boolean forward, boolean back, boolean left, boolean right,
                          boolean fire, boolean dash) implements Serializable {
    public static final PlayerInput NONE = new PlayerInput(false, false, false, false, false, false);
}

package arena.common;

import java.io.Serializable;

public record PickupView(int id, int type, double x, double y) implements Serializable {
    public static final int REPAIR = 0;
    public static final int OVERCLOCK = 1;
}

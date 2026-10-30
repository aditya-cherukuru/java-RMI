package arena.common;

import java.io.Serializable;

/**
 * A directory becomes a mirror (reflects lasers), a file becomes a crate
 * (absorbs lasers and can be destroyed). Coordinates are the top-left corner.
 */
public record Obstacle(int id, String label, double x, double y, double w, double h,
                       boolean mirror, long fileSize, int maxHp) implements Serializable {
}

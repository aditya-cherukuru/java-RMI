package arena.common;

import java.io.Serializable;
import java.util.List;

/** Static level geometry, sent once on join. Built from the server's shared folder. */
public record MapData(int width, int height, List<Obstacle> obstacles, String sourceDir)
        implements Serializable {
}

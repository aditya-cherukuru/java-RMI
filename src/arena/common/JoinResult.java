package arena.common;

import java.io.Serializable;

public record JoinResult(long token, int playerId, int colorRgb, MapData map, String serverInfo)
        implements Serializable {
}

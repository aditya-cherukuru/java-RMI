package arena.common;

import java.io.Serializable;
import java.util.List;

/** Complete world state for one server tick. */
public record GameSnapshot(long tick, List<PlayerView> players, List<BeamView> beams,
                           List<PickupView> pickups, int[] crateHp,
                           Phase phase, double phaseTimeLeft, int killLimit, String winner)
        implements Serializable {

    public enum Phase { WAITING, PLAYING, INTERMISSION }
}

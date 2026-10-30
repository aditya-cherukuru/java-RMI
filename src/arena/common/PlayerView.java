package arena.common;

import java.io.Serializable;

public record PlayerView(int id, String name, int colorRgb, double x, double y, double angle,
                         int hp, int kills, int deaths, int streak, boolean alive,
                         boolean shielded, boolean overclocked, boolean dashing,
                         double respawnIn, double fireCooldown, double dashCooldown)
        implements Serializable {
}

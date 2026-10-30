package arena.common;

import java.io.Serializable;

/** A fired laser: a polyline (x0,y0,x1,y1,...) that fades out over a few ticks. */
public record BeamView(long id, int ownerId, int colorRgb, float[] points, double age, int bounces)
        implements Serializable {
}

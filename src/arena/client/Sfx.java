package arena.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Tiny synthesizer: all sounds are generated in code, no audio files needed. */
public final class Sfx {
    static final int LASER = 0, HIT = 1, BOOM = 2, PICKUP = 3, ROUND = 4, CRUNCH = 5;

    private static final float RATE = 22050f;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int MAX_VOICES = 8;

    private final short[][] sounds = new short[6][];
    private final ExecutorService player = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sfx");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger voices = new AtomicInteger();
    private volatile boolean muted;
    private volatile boolean broken;

    public Sfx() {
        Random rnd = new Random(7);
        sounds[LASER] = synth(0.16, (t, d) -> {
            double f = 1500 * Math.pow(0.18, t / d);
            return (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.signum(Math.sin(2 * Math.PI * f * 0.5 * t)))
                    * Math.exp(-t * 14) * 0.5;
        });
        sounds[HIT] = synth(0.12, (t, d) -> ((rnd.nextDouble() * 2 - 1) * 0.6 + Math.sin(2 * Math.PI * 140 * t))
                * Math.exp(-t * 30) * 0.6);
        double[] lp = {0};
        sounds[BOOM] = synth(0.7, (t, d) -> {
            lp[0] += ((rnd.nextDouble() * 2 - 1) - lp[0]) * 0.08;
            return (lp[0] * 2.2 + 0.6 * Math.sin(2 * Math.PI * (70 - 40 * t) * t)) * Math.exp(-t * 5) * 0.7;
        });
        sounds[PICKUP] = synth(0.22, (t, d) -> Math.sin(2 * Math.PI * (t < 0.08 ? 660 : t < 0.15 ? 880 : 1320) * t)
                * Math.exp(-t * 8) * 0.45);
        sounds[ROUND] = synth(0.5, (t, d) -> {
            double f = t < 0.12 ? 523 : t < 0.24 ? 659 : 784;
            return (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t)) * Math.exp(-t * 3) * 0.35;
        });
        sounds[CRUNCH] = synth(0.25, (t, d) -> (rnd.nextDouble() * 2 - 1) * (Math.sin(2 * Math.PI * 30 * t) > 0 ? 1 : 0.3)
                * Math.exp(-t * 12) * 0.5);
    }

    private interface Wave {
        double sample(double t, double duration);
    }

    private static short[] synth(double seconds, Wave wave) {
        short[] out = new short[(int) (seconds * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double fade = Math.min(1, (out.length - i) / 200.0);
            double v = Math.max(-1, Math.min(1, wave.sample(t, seconds) * fade));
            out[i] = (short) (v * 32000);
        }
        return out;
    }

    public void toggleMute() {
        muted = !muted;
    }

    public boolean muted() {
        return muted;
    }

    void play(int sound, float volume) {
        if (muted || broken || voices.get() >= MAX_VOICES) return;
        short[] pcm = sounds[sound];
        player.execute(() -> {
            try {
                byte[] bytes = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    int v = (int) (pcm[i] * volume);
                    bytes[2 * i] = (byte) v;
                    bytes[2 * i + 1] = (byte) (v >> 8);
                }
                Clip clip = AudioSystem.getClip();
                clip.open(FORMAT, bytes, 0, bytes.length);
                voices.incrementAndGet();
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) {
                        clip.close();
                        voices.decrementAndGet();
                    }
                });
                clip.start();
            } catch (Exception | LinkageError e) {
                broken = true;   // no audio device: play silently
            }
        });
    }
}

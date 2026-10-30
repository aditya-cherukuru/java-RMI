package arena.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny synthesizer: all sounds are generated in code, no audio files needed.
 * Sounds: laser (layered sweep), hit (distorted thump), boom (deep explosion),
 * pickup (cheerful arp), round fanfare (major chord), crate crunch.
 * Music: 8-bar ambient electronic loop generated once and looped.
 */
public final class Sfx {
    static final int LASER = 0, HIT = 1, BOOM = 2, PICKUP = 3, ROUND = 4, CRUNCH = 5, DASH = 6, SHIELD = 7;

    private static final float RATE = 44100f;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int MAX_VOICES = 12;

    private final short[][] sounds = new short[8][];
    private final ExecutorService player = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "sfx");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger voices = new AtomicInteger();
    private volatile boolean muted;
    private volatile boolean broken;

    // background music
    private Clip musicClip;
    private volatile boolean musicRunning = false;

    private interface Wave {
        double sample(double t, double duration);
    }

    public Sfx() {
        Random rnd = new Random(42);

        // LASER – layered: a falling sweep + harmonic + noise ring
        sounds[LASER] = synth(0.22, (t, d) -> {
            double f = 1800 * Math.pow(0.08, t / d);
            double fundamental = Math.sin(2 * Math.PI * f * t);
            double harmonic = 0.4 * Math.sin(2 * Math.PI * f * 2.1 * t);
            double ring = 0.2 * Math.sin(2 * Math.PI * 3200 * t) * Math.exp(-t * 35);
            return (fundamental + harmonic + ring) * Math.exp(-t * 8) * 0.55;
        });

        // HIT – punchy distorted thump with high-freq crack
        sounds[HIT] = synth(0.18, (t, d) -> {
            double noise = (rnd.nextDouble() * 2 - 1) * 0.7;
            double tone = Math.sin(2 * Math.PI * (200 - 100 * t / d) * t);
            double crack = Math.sin(2 * Math.PI * 3000 * t) * Math.exp(-t * 60);
            return (noise * 0.4 + tone * 0.4 + crack * 0.3) * Math.exp(-t * 25) * 0.7;
        });

        // BOOM – deep layered explosion with low rumble
        double[] lp = {0};
        sounds[BOOM] = synth(0.9, (t, d) -> {
            lp[0] += ((rnd.nextDouble() * 2 - 1) - lp[0]) * 0.06;
            double sub = Math.sin(2 * Math.PI * (55 - 30 * t / d) * t);
            double mid = Math.sin(2 * Math.PI * (180 - 80 * t / d) * t) * 0.5;
            double rumble = lp[0] * 2.5;
            return (sub + mid + rumble) * Math.exp(-t * 3.5) * 0.75;
        });

        // PICKUP – cheerful rising arpeggio with harmonics
        sounds[PICKUP] = synth(0.32, (t, d) -> {
            double[] freqs = {523, 659, 784, 1047};
            int idx = (int) Math.min(3, t / d * 4);
            double f = freqs[idx];
            return (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t) + 0.15 * Math.sin(6 * Math.PI * f * t))
                    * Math.exp(-t * 5) * 0.45;
        });

        // ROUND – triumphant C-E-G-C fanfare
        sounds[ROUND] = synth(0.9, (t, d) -> {
            double[] notes = {523.25, 659.25, 783.99, 1046.5};
            int seg = (int) Math.min(3, t / d * 4);
            double f = notes[seg];
            double v = Math.sin(2 * Math.PI * f * t)
                    + 0.5 * Math.sin(2 * Math.PI * f * 2 * t)
                    + 0.25 * Math.sin(2 * Math.PI * f * 3 * t);
            return v * Math.exp(-((t % (d / 4)) * 8)) * 0.35;
        });

        // CRUNCH – wood-shattering low distortion
        sounds[CRUNCH] = synth(0.35, (t, d) -> {
            double n = (rnd.nextDouble() * 2 - 1);
            double body = Math.sin(2 * Math.PI * 80 * t) * Math.exp(-t * 15);
            double splinter = n * Math.exp(-t * 18) * (t < 0.05 ? 1.0 : 0.3);
            return (body + splinter) * 0.55;
        });

        // DASH – whoosh air burst
        double[] hp = {0};
        sounds[DASH] = synth(0.28, (t, d) -> {
            hp[0] += ((rnd.nextDouble() * 2 - 1) - hp[0]) * 0.25;
            double freq = 800 + 600 * (1 - t / d);
            double tone = Math.sin(2 * Math.PI * freq * t) * 0.3;
            return (hp[0] * 0.6 + tone) * Math.exp(-t * 5) * (1 - t / d) * 0.6;
        });

        // SHIELD UP – rising crystalline ping
        sounds[SHIELD] = synth(0.5, (t, d) -> {
            double f = 800 + 1600 * t / d;
            return (Math.sin(2 * Math.PI * f * t) * 0.7 + Math.sin(2 * Math.PI * f * 2.003 * t) * 0.3)
                    * Math.exp(-t * 4) * 0.4;
        });

        // Start ambient background music
        startMusic();
    }

    // ------------------------------------------------------------------ music

    /**
     * Synthesizes and starts a looping ambient electronic music track.
     * Uses a simple but effective chord progression: Am - F - C - G (i-VI-III-VII in A minor).
     * Layers: bass, pad chords, arpeggio, kick/snare rhythm.
     */
    private void startMusic() {
        player.execute(() -> {
            try {
                float musicRate = 44100f;
                AudioFormat musicFmt = new AudioFormat(musicRate, 16, 2, true, false);
                // 4 bars at 120 bpm = 8 seconds loop
                double bpm = 120;
                double barDuration = 60.0 / bpm * 4;
                double loopDuration = barDuration * 4; // 4 bars = 8 sec
                int totalSamples = (int) (loopDuration * musicRate);
                short[] left = new short[totalSamples];
                short[] right = new short[totalSamples];

                Random mr = new Random(99);

                // Chord progression: Am  F  C  G  (root freqs in Hz)
                double[][] chords = {
                        {220.0, 261.63, 329.63},  // Am
                        {174.61, 220.0, 261.63},  // F
                        {130.81, 164.81, 196.0},  // C
                        {196.0, 246.94, 293.66},  // G
                };
                double[] bassNotes = {55.0, 43.65, 65.41, 49.0}; // A1, F1, C2, G1

                for (int i = 0; i < totalSamples; i++) {
                    double t = i / musicRate;
                    double barPos = (t % loopDuration) / barDuration; // 0..4
                    int chordIdx = (int) Math.floor(barPos) % 4;
                    double barT = (barPos - Math.floor(barPos)); // 0..1 within bar

                    // --- PAD chords (slow attack, lush sustain)
                    double pad = 0;
                    for (double f : chords[chordIdx]) {
                        pad += Math.sin(2 * Math.PI * f * t) * 0.25;
                        pad += Math.sin(2 * Math.PI * f * 2.001 * t) * 0.12; // slight detune
                    }
                    double padEnv = Math.min(1, barT * 8) * Math.exp(-barT * 0.4);
                    pad *= padEnv;

                    // --- BASS (punchy, note on each bar start)
                    double bass = 0;
                    double beatT = (t % (60.0 / bpm));
                    bass = (Math.sin(2 * Math.PI * bassNotes[chordIdx] * t)
                            + 0.5 * Math.signum(Math.sin(2 * Math.PI * bassNotes[chordIdx] * t)))
                            * Math.exp(-beatT * 6) * 0.35;

                    // --- ARPEGGIO (16th-note pattern)
                    double sixteenth = 60.0 / bpm / 4;
                    double arpT = t % sixteenth;
                    int arpStep = (int) (t / sixteenth) % chords[chordIdx].length;
                    double arpFreq = chords[chordIdx][arpStep % chords[chordIdx].length] * 4; // 2 octaves up
                    double arp = Math.sin(2 * Math.PI * arpFreq * t)
                            * Math.exp(-arpT * 20) * 0.18;

                    // --- KICK (4-on-floor)
                    double beatSixteenth = t % (60.0 / bpm);
                    double kick = (Math.sin(2 * Math.PI * (80 - 60 * beatSixteenth / (60.0 / bpm)) * t))
                            * Math.exp(-beatSixteenth * 18) * 0.55;

                    // --- HI-HAT (every 2 sixteenths, off-beat)
                    double hatPeriod = 60.0 / bpm / 2;
                    double hatT = t % hatPeriod;
                    double hat = (mr.nextDouble() * 2 - 1) * Math.exp(-hatT * 60) * 0.12;

                    double mix = pad * 0.4 + bass * 0.5 + arp * 0.7 + kick * 0.45 + hat;
                    mix = Math.max(-0.85, Math.min(0.85, mix));

                    short sample = (short) (mix * 24000);
                    // slight stereo spread on pad
                    double padStereo = pad * padEnv * 0.07;
                    left[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample - (int)(padStereo * 24000)));
                    right[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample + (int)(padStereo * 24000)));
                }

                // interleave stereo
                byte[] stereoBytes = new byte[totalSamples * 4];
                for (int i = 0; i < totalSamples; i++) {
                    short l = left[i], r = right[i];
                    stereoBytes[i * 4] = (byte) l;
                    stereoBytes[i * 4 + 1] = (byte) (l >> 8);
                    stereoBytes[i * 4 + 2] = (byte) r;
                    stereoBytes[i * 4 + 3] = (byte) (r >> 8);
                }

                musicClip = AudioSystem.getClip();
                musicClip.open(musicFmt, stereoBytes, 0, stereoBytes.length);
                // Lower music volume
                if (musicClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gc = (FloatControl) musicClip.getControl(FloatControl.Type.MASTER_GAIN);
                    float min = gc.getMinimum(), max = gc.getMaximum();
                    gc.setValue(min + (max - min) * 0.38f); // about -10 dB
                }
                musicClip.loop(Clip.LOOP_CONTINUOUSLY);
                musicRunning = true;
            } catch (Exception | LinkageError e) {
                broken = true;
            }
        });
    }

    // ------------------------------------------------------------------ SFX

    private static short[] synth(double seconds, Wave wave) {
        int n = (int) (seconds * RATE);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double fade = Math.min(1, (n - i) / (RATE * 0.005)); // 5 ms tail fade
            double v = Math.max(-1, Math.min(1, wave.sample(t, seconds) * fade));
            out[i] = (short) (v * 32000);
        }
        return out;
    }

    public void toggleMute() {
        muted = !muted;
        if (musicClip != null) {
            if (muted) {
                musicClip.stop();
            } else {
                musicClip.loop(Clip.LOOP_CONTINUOUSLY);
            }
        }
    }

    public boolean muted() {
        return muted;
    }

    void play(int sound, float volume) {
        if (muted || broken || voices.get() >= MAX_VOICES) return;
        if (sound < 0 || sound >= sounds.length || sounds[sound] == null) return;
        short[] pcm = sounds[sound];
        player.execute(() -> {
            try {
                byte[] bytes = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    int v = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm[i] * volume));
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
                broken = true;
            }
        });
    }
}

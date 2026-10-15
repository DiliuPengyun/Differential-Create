package com.diliupengyun.differential_create.client;

/** Two exhaust beats per input revolution, independent of wheel motion or gear. */
public final class SteamSoundAnimation {
    private double previous;
    private int direction;
    private boolean seeded;

    public boolean advance(double turns, double rpm, double supply) {
        int nextDirection = (int) Math.signum(rpm);
        if (!Double.isFinite(turns) || !Double.isFinite(rpm) || !Double.isFinite(supply) || supply <= 0 || nextDirection == 0) {
            seeded = false; return false;
        }
        if (!seeded || direction != nextDirection) {
            previous = turns; direction = nextDirection; seeded = true; return false;
        }
        // A corrected sample must not replay a beat in the opposite direction.
        if ((turns - previous) * direction <= 0) return false;
        boolean beat = Math.floor(turns * 2 * direction) != Math.floor(previous * 2 * direction);
        previous = turns;
        return beat;
    }
}

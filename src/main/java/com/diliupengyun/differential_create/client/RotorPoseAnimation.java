package com.diliupengyun.differential_create.client;

/** A member and its optional output spindle play the same physical snapshot.
 * Queries only read frames; the client tick advances both angles together. */
public final class RotorPoseAnimation {
    private final RotorAnimation member = new RotorAnimation();
    private RotorAnimation output;

    public void receive(double memberAngle, Double outputAngle) {
        member.receive(memberAngle);
        if (outputAngle == null) output = null;
        else {
            if (output == null) output = new RotorAnimation();
            output.receive(outputAngle);
        }
    }

    public void advance(int tick, double renderTick, boolean frozen) {
        member.advance(tick, renderTick, frozen);
        if (output != null) output.advance(tick, renderTick, frozen);
    }

    public RotationFrame memberFrame() { return member.frame(); }
    public RotationFrame outputFrame() { return output == null ? null : output.frame(); }
}

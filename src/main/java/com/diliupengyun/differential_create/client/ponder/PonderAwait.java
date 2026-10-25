package com.diliupengyun.differential_create.client.ponder;

import java.util.function.Predicate;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.PonderInstruction;

/** A result cue waits for the machine; waiting never writes the expected result. */
public final class PonderAwait extends PonderInstruction {
    private final Predicate<PonderScene> ready;
    private final int limit;
    private boolean complete;
    private int elapsed;
    public PonderAwait(Predicate<PonderScene> ready, int limit) { this.ready = ready; this.limit = limit; }
    @Override public boolean isBlocking() { return true; }
    @Override public boolean isComplete() { return complete; }
    @Override public void reset(PonderScene scene) { complete = false; elapsed = 0; }
    @Override public void onScheduled(PonderScene scene) {
        // The result check itself occupies one tick. Storyboards reserve the
        // measured work time with idle() before this safeguard, so ordinary
        // playback and keyframes use the native, continuously advancing clock.
        scene.addToSceneTime(1);
    }
    @Override public void tick(PonderScene scene) {
        complete = ready.test(scene);
        // A changed recipe/configuration must not crash the player's client.
        // Verification separately asserts the actual result, including timeouts.
        if (!complete && ++elapsed >= limit) complete = true;
    }
}

package com.diliupengyun.differential_create.client.ponder;

import java.util.Map;
import java.util.WeakHashMap;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;

/** Continue the paused pose through the remainder of the current UI interval. */
public final class PonderPlayback {
    private record Resume(PonderScene scene, long tick, float paused, float start) {}
    private static final Map<PonderUI, Resume> resumes = new WeakHashMap<>();

    private PonderPlayback() {}

    public static void clear(PonderUI ui) { resumes.remove(ui); }

    public static void resume(PonderUI ui, float paused, float start) {
        var scene = ui.getActiveScene();
        resumes.put(ui, new Resume(scene, PonderSimulation.of(scene.getWorld()).time(), paused, start));
    }

    public static float partial(PonderUI ui, float partial) {
        var resume = resumes.get(ui);
        if (resume == null) return partial;
        if (ui.getActiveScene() != resume.scene()
            || PonderSimulation.of(resume.scene().getWorld()).time() != resume.tick()) {
            resumes.remove(ui);
            return partial;
        }
        // The UI timer keeps running during identification. Rebase its remaining
        // fraction onto the captured pose, then use ordinary interpolation again
        // as soon as the next physical frame is available.
        float progress = resume.start() >= 1 ? 1 : (partial - resume.start()) / (1 - resume.start());
        return resume.paused() + Math.clamp(progress, 0, 1) * (1 - resume.paused());
    }
}

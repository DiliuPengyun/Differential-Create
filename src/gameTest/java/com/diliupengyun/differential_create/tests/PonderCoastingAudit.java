package com.diliupengyun.differential_create.tests;

import java.util.List;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;

/** A cut-power caption needs a visible disconnection and genuinely unpowered machinery. */
final class PonderCoastingAudit {
    private record Lesson(String id, int caption, BlockPos gap, BlockPos output, boolean moving) {}
    private static final List<Lesson> LESSONS = List.of(
        new Lesson("load_gauge", 3, new BlockPos(3, 1, 2), new BlockPos(2, 1, 2), true),
        new Lesson("pump_direction", 3, new BlockPos(3, 1, 3), new BlockPos(2, 1, 2), true),
        new Lesson("bearing", 3, new BlockPos(3, 1, 2), new BlockPos(2, 2, 2), true),
        new Lesson("ejector_work", 2, new BlockPos(4, 1, 3), new BlockPos(4, 1, 2), false),
        new Lesson("fan_work", 3, new BlockPos(2, 2, 5), new BlockPos(2, 2, 4), true));
    private boolean displayed, coasting;

    private static Lesson lesson(PonderScene scene) {
        return scene.getId().getNamespace().equals("differentialcreate")
            ? LESSONS.stream().filter(l -> l.id().equals(scene.getId().getPath())).findFirst().orElse(null) : null;
    }

    void sample(PonderScene scene, List<String> captions) {
        var lesson = lesson(scene);
        if (lesson == null || !captions.contains(PonderIndex.getLangAccess().getSpecific(scene.getId(), "text_" + lesson.caption()))) return;
        displayed = true;
        if (!scene.getWorld().getBlockState(lesson.gap()).isAir())
            throw new AssertionError("Cut-power caption has no visible input break in " + scene.getId());
        if (!(scene.getWorld().getBlockEntity(lesson.output()) instanceof KineticBlockEntity output)
            || PonderKinetics.of(scene.getWorld()).loadReading(output).status() != LoadFactor.Status.NO_POWER)
            throw new AssertionError("Disconnected machinery still receives source power in " + scene.getId());
        coasting |= Math.abs(output.getSpeed()) > 1;
    }

    void verify(PonderScene scene) {
        var lesson = lesson(scene);
        if (lesson != null && (!displayed || lesson.moving() && !coasting))
            throw new AssertionError("Missing visible unpowered motion during the cut-power caption in " + scene.getId());
    }
}

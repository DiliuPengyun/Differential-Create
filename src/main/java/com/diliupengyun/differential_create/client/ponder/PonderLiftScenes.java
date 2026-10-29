package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Keep native lift layouts and captions; distinguish framing from a floor request. */
public final class PonderLiftScenes {
    public static final BlockPos MULTI_PRIMARY = new BlockPos(3, 4, 1), ELEVATOR = new BlockPos(3, 18, 2);
    private PonderLiftScenes() {}

    public static void multiAttach(SceneBuilder builder, ElementLink<WorldSectionElement> link) {
        builder.addInstruction(scene -> {
            var sim = PonderSimulation.of(scene.getWorld());
            var primary = (PulleyBlockEntity) scene.getWorld().getBlockEntity(MULTI_PRIMARY);
            sim.attach(scene.resolve(link), primary, false, 1, -1);
            for (var pos : new BlockPos[]{new BlockPos(3, 4, 3), new BlockPos(1, 4, 3)})
                sim.followPulley((PulleyBlockEntity) scene.getWorld().getBlockEntity(pos), primary);
        });
    }
    public static void elevatorAssembly(PonderScene scene, boolean assemble) {
        var sim = PonderSimulation.of(scene.getWorld());
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (!((PonderWorldSectionAccessor) section).differentialcreate$selection().test(new BlockPos(3, 17, 2))) return;
            if (assemble) sim.attach(section, (PulleyBlockEntity) scene.getWorld().getBlockEntity(ELEVATOR), false, 1, -1);
            else sim.release(section);
        });
    }
    public static void presentation(SceneBuilder builder, ElementLink<WorldSectionElement> link, Vec3 delta, int duration) {
        builder.addInstruction(new TickingInstruction(false, Math.max(1, duration)) {
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                var section = scene.resolve(link);
                if (section != null) PonderSimulation.of(scene.getWorld()).translatePresentation(section,
                    delta.scale(1d / Math.max(1, duration)), duration == 0);
            }
        });
    }
}

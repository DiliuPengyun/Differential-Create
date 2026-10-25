package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Native item/fluid redstone composition, with an actual initial docking session. */
public final class PonderInterfaceRedstoneScene {
    private PonderInterfaceRedstoneScene() {}

    public static void redstone(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        scene.title("portable_storage_interface_redstone", "Redstone Control");
        scene.configureBasePlate(0, 0, 5);
        scene.setSceneOffsetY(-1);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).useExplicitSources());

        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(5);
        scene.world().showSection(util.select().layer(1), Direction.DOWN);
        scene.idle(5);
        var body = scene.world().showIndependentSection(util.select().layersFrom(2), Direction.DOWN);
        var bearing = new BlockPos(3, 1, 3);
        scene.world().configureCenterOfRotation(body, util.vector().topOf(bearing));
        // Assemble at rest so contact, extension and inventory access settle
        // before applying drive. No Timer or connection result is scripted.
        scene.addInstruction(s -> {
            var world = s.getWorld();
            PonderKinetics.of(world).boundary(world, bearing, 0);
            PonderSimulation.of(world).attach(s.resolve(body),
                (KineticBlockEntity) world.getBlockEntity(bearing), true, 1, 1);
        });
        scene.idle(20);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), bearing, 15));
        // Like Create's 300-tick rotation instruction, motion continues after
        // the completion marker. Only the original idle instructions set the bar.
        scene.addInstruction(new TickingInstruction(false, 320) {
            private int elapsed;
            @Override public void reset(PonderScene s) { super.reset(s); elapsed = 0; }
            @Override public void tick(PonderScene s) {
                super.tick(s);
                if (++elapsed != 300) return;
                var physics = PonderKinetics.of(s.getWorld());
                physics.boundary(s.getWorld(), bearing, 0);
                physics.supply(s.getWorld(), bearing, new SourcePhysics.Curve(0, 64));
            }
        });
        scene.idle(20);

        scene.world().toggleRedstonePower(util.select().fromTo(1, 1, 1, 1, 1, 2));
        scene.effects().indicateRedstone(util.grid().at(1, 1, 1));
        scene.idle(10);
        scene.overlay().showOutlineWithText(util.select().position(1, 1, 3), 120)
            .colored(PonderPalette.RED)
            .text("Redstone power will prevent the stationary interface from engaging");

        scene.idle(20);
        scene.markAsFinished();
    }
}

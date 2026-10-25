package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint;
import com.simibubi.create.AllFluids;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Native fluid-interface composition and eight captions; only inputs and waiting times change. */
public final class PonderFluidInterfaceScene {
    private static final BlockPos BEARING = new BlockPos(5, 1, 2);
    private static final BlockPos MOVING = new BlockPos(4, 2, 2);
    private static final BlockPos STATION = new BlockPos(2, 2, 2);

    private PonderFluidInterfaceScene() {}

    public static void transfer(SceneBuilder builder, SceneBuildingUtil util) {
        var scene = new CreateSceneBuilder(builder);
        scene.title("portable_fluid_interface", "Contraption Fluid Exchange");
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(.95f);
        scene.setSceneOffsetY(-1);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).useExplicitSources());
        scene.showBasePlate();
        scene.idle(5);
        var pump = new BlockPos(0, 1, 4);
        var source = new BlockPos(0, 1, 5);
        var chocolate = new FluidStack(FluidHelper.convertToStill(AllFluids.CHOCOLATE.get()), 24000);
        var bucket = chocolate.getFluid().getFluidType().getBucket(chocolate);
        scene.world().modifyBlock(pump, s -> s.setValue(PumpBlock.FACING, Direction.NORTH), false);
        scene.world().modifyBlockEntity(source, FluidTankBlockEntity.class,
            tank -> tank.getTankInventory().fill(chocolate, FluidAction.EXECUTE));
        scene.world().showSection(util.select().position(BEARING), Direction.DOWN);
        scene.idle(5);
        var body = scene.world().showIndependentSection(util.select().fromTo(5, 2, 2, 6, 4, 2), Direction.DOWN);
        scene.world().configureCenterOfRotation(body, util.vector().centerOf(BEARING));
        scene.idle(10);
        turn(scene, body, 360, 70, true);
        scene.overlay().showText(60).pointAt(util.vector().topOf(BEARING.above(2)))
            .colored(PonderPalette.RED).placeNearTarget().attachKeyFrame()
            .text("Fluid Tanks on moving contraptions cannot be accessed by any pipes");
        // The solved turn settles after about 81 ticks; leave only a short beat
        // before installation, rather than the controller's full safety window.
        scene.idle(85);

        // Release the stopped assembly for installation, then assemble it again
        // for the next rotation, after explaining the stationary port's spacing.
        scene.world().showSectionAndMerge(util.select().position(MOVING), Direction.EAST, body);
        scene.idle(13);
        scene.effects().superGlue(MOVING, Direction.EAST, true);
        scene.overlay().showText(80).pointAt(util.vector().topOf(MOVING))
            .colored(PonderPalette.GREEN).placeNearTarget().attachKeyFrame()
            .text("This component can interact with fluid tanks without the need to stop the contraption");
        scene.idle(90);
        scene.world().showSection(util.select().position(STATION), Direction.DOWN);
        scene.overlay().showOutlineWithText(util.select().position(MOVING.west()), 50)
            .colored(PonderPalette.RED).placeNearTarget().attachKeyFrame()
            .text("Place a second one with a gap of 1 or 2 blocks inbetween");
        scene.idle(65);

        turn(scene, body, 360, 60, false);
        scene.idle(40);
        scene.overlay().showText(40).placeNearTarget().pointAt(util.vector().of(3, 3, 2.5))
            .text("Whenever they pass by each other, they will engage in a connection");
        scene.idle(45);
        scene.overlay().showOutline(PonderPalette.GREEN, MOVING, util.select().fromTo(5, 3, 2, 6, 4, 2), 65);
        scene.overlay().showOutlineWithText(util.select().position(STATION), 65)
            .placeNearTarget().colored(PonderPalette.GREEN).attachKeyFrame()
            .text("While engaged, the stationary interface will represent ALL Tanks on the contraption");

        // Reveal the native supply during the connected-port explanation. Real
        // transfer must renew the session before its inactivity timeout expires.
        var supply = scene.world().showIndependentSection(util.select().fromTo(0, 1, 5, 0, 3, 5), Direction.DOWN);
        scene.world().moveSection(supply, util.vector().of(0, 0, -1), 0);
        scene.idle(5);
        scene.world().showSectionAndMerge(util.select().fromTo(2, 1, 3, 0, 1, 3).add(util.select().position(pump)), Direction.EAST, supply);
        scene.idle(5);
        scene.world().showSectionAndMerge(util.select().position(2, 0, 7), Direction.UP, supply);
        scene.world().showSectionAndMerge(util.select().fromTo(1, 1, 7, 1, 1, 4), Direction.NORTH, supply);
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), new BlockPos(2, 0, 7), -96));
        scene.idle(55);
        scene.overlay().showText(300).placeNearTarget().pointAt(util.vector().topOf(pump))
            .attachKeyFrame().text("Fluid can now be inserted...");
        scene.idle(120);
        scene.overlay().showControls(util.vector().blockSurface(new BlockPos(5, 3, 2), Direction.WEST).add(0, .5, 0), Pointing.LEFT, 30)
            .withItem(bucket);
        scene.idle(180);
        scene.overlay().showText(340).placeNearTarget().pointAt(util.vector().topOf(pump))
            .attachKeyFrame().text("...or extracted from the contraption");
        scene.world().modifyBlock(pump, s -> s.setValue(PumpBlock.FACING, Direction.SOUTH), true);
        scene.world().propagatePipeChange(pump);
        scene.idle(340);

        scene.overlay().showText(120).placeNearTarget().attachKeyFrame().pointAt(util.vector().topOf(STATION))
            .text("After no contents have been exchanged for a while, the contraption will continue on its way");
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), new BlockPos(2, 0, 7), 0));
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), BEARING, 7.5f));
        scene.idle(160);
        scene.markAsFinished();
    }

    /** An off-stage operator slows and brakes the input; angle and velocity remain solved. */
    private static void turn(CreateSceneBuilder scene, ElementLink<WorldSectionElement> body,
                             double degrees, int travelTicks, boolean disassemble) {
        float cruise = (float) (degrees / (.3 * travelTicks));
        scene.addInstruction(s -> PonderKinetics.of(s.getWorld()).boundary(s.getWorld(), BEARING, cruise));
        scene.addInstruction(s -> PonderSimulation.of(s.getWorld()).attach(s.resolve(body),
            (KineticBlockEntity) s.getWorld().getBlockEntity(BEARING), true, 1, 1));
        scene.addInstruction(new TickingInstruction(false, travelTicks + 50) {
            private double target;
            private boolean started, stopped, done;
            @Override public void reset(PonderScene s) { super.reset(s); started = stopped = done = false; }
            @Override public void tick(PonderScene s) {
                super.tick(s);
                if (done) return;
                var section = s.resolve(body);
                if (section == null) return;
                var world = s.getWorld();
                var physics = PonderKinetics.of(world);
                if (!started) { target = section.getAnimatedRotation().y + degrees; started = true; }
                if (!disassemble && world.getBlockEntity(STATION) instanceof PortableEndpoint endpoint
                    && endpoint.differentialcreate$docking().occupied()) {
                    physics.boundary(world, BEARING, cruise);
                    done = true;
                    return;
                }
                double error = target - section.getAnimatedRotation().y;
                stopped |= Math.abs(error) < .002 && Math.abs(physics.outputSpeed(BEARING)) < .02;
                physics.boundary(world, BEARING, stopped ? 0 : (float) Math.clamp(error, -Math.abs(cruise), Math.abs(cruise)));
                if (stopped) {
                    physics.setAttachmentLoad(BEARING, 1024);
                    if (Math.abs(physics.outputSpeed(BEARING)) < 1e-6) {
                        if (disassemble) PonderSimulation.of(world).release(section);
                        done = true;
                    }
                }
            }
        });
    }
}

package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Real mixed-rate networks and a recipe callback that joins previously independent shafts. */
final class KineticIslandTests {
    private static final BlockPos CONTROL = new BlockPos(26, 18, 26);
    private static void controller(GameTestHelper h, boolean sequencer) {
        h.setBlock(CONTROL, sequencer ? AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState()
            .setValue(SequencedGearshiftBlock.VERTICAL, false).setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X)
            : AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(CONTROL.west(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        ((CreativeMotorBlockEntity) h.getBlockEntity(CONTROL.west())).generatedSpeed.setValue(32);
        if (sequencer) h.setBlock(CONTROL.east(), AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        else {
            h.setBlock(CONTROL.east(), Blocks.SLIME_BLOCK); h.setBlock(CONTROL.east().above(), Blocks.SLIME_BLOCK);
            h.setBlock(CONTROL.east(2), Blocks.HONEY_BLOCK); h.setBlock(CONTROL.east(2).above(), Blocks.HONEY_BLOCK);
        }
    }
    private static KineticBlockEntity flywheel(GameTestHelper h, BlockPos pos, double speed) {
        h.setBlock(pos, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        KineticBlockEntity be = h.getBlockEntity(pos); NetworkTopology.mark(be); NetworkTopology.flush();
        ElasticKinetics.state(be).physicalSpeed = speed;
        return be;
    }
    static void isolation(GameTestHelper h, boolean sequencer) {
        controller(h, sequencer);
        h.setBlock(new BlockPos(6, 10, 18), com.diliupengyun.differential_create.content.ModContent.BRASS_CLUTCH.getDefaultState());
        var pulleyPos = new BlockPos(12, 20, 12);
        h.setBlock(pulleyPos, AllBlocks.ROPE_PULLEY.getDefaultState()); h.setBlock(pulleyPos.below(), Blocks.SLIME_BLOCK);
        h.runAfterDelay(10, () -> {
            KineticBlockEntity control = h.getBlockEntity(CONTROL);
            if (sequencer) {
                var seq = (SequencedGearshiftBlockEntity) control;
                seq.getInstructions().clear(); seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_ANGLE, InstructionSpeedModifiers.getByModifier(1), 360));
                seq.getInstructions().add(new Instruction(SequencerInstructions.END)); seq.run(0);
            } else h.assertTrue(MachiningClock.needsPositionControl(control), "assembled clock needs 64 control samples");
            LinearActuatorBlockEntity pulley = h.getBlockEntity(pulleyPos);
            ElasticKinetics.state(pulley).physicalSpeed = 16;
            try { ((LinearActuatorAccessor) pulley).differentialcreate$assemble(); }
            catch (Exception e) { throw new AssertionError("pulley assembly", e); }
            ElasticKinetics.state(pulley).physicalSpeed = 0;
            h.assertTrue(pulley.movedContraption != null, "ordinary moving payload assembled");
            var ordinary = new ArrayList<KineticBlockEntity>();
            for (int i = 0; i < 8; i++) ordinary.add(flywheel(h, new BlockPos(3 + i * 3, 10, 5), 40));
            var angles = ordinary.stream().mapToDouble(be -> ElasticKinetics.state(be).angle).toArray();
            try (var trace = new ClockTestScope()) {
                MachiningClock.advance(h.getLevel());
                // The full suite can leave other working machines in loaded chunks.
                // Count their legitimate world barriers separately from control-only steps.
                h.assertTrue(trace.maximum(control) <= RotationMath.TICK_MINUTES / 64 + 1e-14, "controller keeps its sampling precision");
                h.assertTrue(trace.maximum(pulley) <= RotationMath.TICK_MINUTES / 8 + 1e-14, "pulley keeps its sampling precision");
                h.assertTrue(trace.steps(control) >= 64 && trace.steps(control) <= 64 + trace.barriers(), "controller captures plus event barriers");
                h.assertTrue(trace.steps(pulley) >= 8 && trace.steps(pulley) <= 8 + trace.barriers(), "ordinary pulley is not resampled by unrelated control steps");
                for (int i = 0; i < ordinary.size(); i++) {
                    var be = ordinary.get(i);
                    h.assertTrue(trace.steps(be) <= 1 + trace.barriers(), "unrelated flywheel needs one capture plus actual world barriers: " + trace.steps(be));
                    near(h, RotationMath.TICK_MINUTES, trace.time(be), "ordinary rotor receives exactly one tick");
                    near(h, 40 * RotationMath.TICK_MINUTES, ElasticKinetics.state(be).angle - angles[i], "analytic free-rotor displacement");
                    near(h, 40, NetworkDynamics.preciseSpeed(be), "free rotor retains speed");
                }
                near(h, RotationMath.TICK_MINUTES, trace.time(control), "controller time");
                near(h, RotationMath.TICK_MINUTES, trace.time(pulley), "pulley time");
                System.out.println("[DC islands] " + (sequencer ? "sequencer" : "clock") + " control=" + trace.steps(control)
                    + " pulley=" + trace.steps(pulley) + " free=" + trace.steps(ordinary.getFirst()) + " worldBarriers=" + trace.barriers());
            }
            pulley.disassemble(); h.succeed();
        });
    }
    static void recipeBarrier(GameTestHelper h) {
        recipeBarrier(h, false);
    }
    private static final class ExternalMotor extends CreativeMotorBlockEntity {
        ExternalMotor(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            super(com.simibubi.create.AllBlockEntityTypes.MOTOR.get(), pos, state);
        }
    }
    static void unknownMachine(GameTestHelper h) {
        controller(h, false);
        var pos = new BlockPos(5, 10, 18);
        h.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState());
        h.runAfterDelay(10, () -> {
            h.getLevel().removeBlockEntity(h.absolutePos(pos));
            var external = new ExternalMotor(h.absolutePos(pos), h.getBlockState(pos));
            h.getLevel().setBlockEntity(external); external.initialize(); NetworkTopology.mark(external); NetworkTopology.flush();
            var free = flywheel(h, new BlockPos(10, 10, 18), 40);
            KineticBlockEntity control = h.getBlockEntity(CONTROL);
            try (var trace = new ClockTestScope()) {
                MachiningClock.advance(h.getLevel());
                h.assertTrue(trace.steps(control) >= 64 && trace.steps(free) == trace.steps(control),
                    "unknown machine preserves the common clock until its cross-network reads are known");
                near(h, RotationMath.TICK_MINUTES, trace.time(free), "fallback preserves full tick time");
                near(h, 40, NetworkDynamics.preciseSpeed(free), "fallback preserves free-rotor speed");
            } finally { h.setBlock(pos, Blocks.AIR); NetworkTopology.flush(); }
            h.succeed();
        });
    }
    static void recipeBarrier(GameTestHelper h, boolean fracture) {
        controller(h, false);
        var center = new BlockPos(8, 5, 8);
        for (var pos : List.of(center.west(), center.east()))
            h.setBlock(pos, AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        h.runAfterDelay(10, () -> {
            CrushingWheelBlockEntity a = h.getBlockEntity(center.west()), b = h.getBlockEntity(center.east());
            NetworkTopology.mark(a); NetworkTopology.mark(b); NetworkTopology.flush();
            AllBlocks.CRUSHING_WHEEL.get().updateControllers(a.getBlockState(), h.getLevel(), a.getBlockPos(), Direction.EAST);
            CrushingWheelControllerBlockEntity crusher = h.getBlockEntity(center);
            var pair = CrushingKinetics.pair(crusher);
            h.assertTrue(pair != null && a.getOrCreateNetwork() != b.getOrCreateNetwork(), "two native networks have one material contact");
            crusher.inventory.insertItem(0, new ItemStack(Items.COBBLESTONE, 1), false);
            var work = (ShaftWork) crusher;
            work.differentialcreate$advanceTravel(work.differentialcreate$remainingTravel() - 32);
            ElasticKinetics.state(a).physicalSpeed = 128; ElasticKinetics.state(b).physicalSpeed = -64;
            CrushingKinetics.update(crusher);
            var leftPos = new BlockPos(18, 10, 5);
            // A gentle connection tests regrouping without intentionally snapping the new shaft.
            double leftSpeed = fracture ? 40 : .04, rightSpeed = fracture ? -20 : -.02;
            var left = flywheel(h, leftPos, leftSpeed); var right = flywheel(h, leftPos.east(2), rightSpeed);
            double leftAngle = ElasticKinetics.state(left).angle, rightAngle = ElasticKinetics.state(right).angle;
            double momentum = InertiaRegistry.getInertia(left) * leftSpeed + InertiaRegistry.getInertia(right) * rightSpeed;
            var callbacks = new AtomicInteger();
            try (var trace = new ClockTestScope()) {
                Consumer<EntityJoinLevelEvent> onOutput = event -> {
                    if (event.getLevel() != h.getLevel() || !(event.getEntity() instanceof ItemEntity item) || !item.getItem().is(Items.GRAVEL)
                        || item.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(center))) > 3) return;
                    callbacks.incrementAndGet();
                    double at = trace.time(a);
                    h.assertTrue(at > 0 && at < RotationMath.TICK_MINUTES, "recipe completes inside the tick");
                    near(h, at, trace.time(b), "both crusher wheels share event time");
                    near(h, at, trace.time(left), "unrelated rotor catches up before world callback");
                    near(h, at, trace.time(right), "second rotor catches up before world callback");
                    near(h, leftSpeed * at, ElasticKinetics.state(left).angle - leftAngle, "callback sees current left phase");
                    near(h, rightSpeed * at, ElasticKinetics.state(right).angle - rightAngle, "callback sees current right phase");
                    h.setBlock(leftPos.east(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
                    // Native placement defers initialization until its BE tick.
                    // Exercise an actual same-tick attach notification here.
                    NetworkTopology.mark(h.getBlockEntity(leftPos.east()));
                };
                NeoForge.EVENT_BUS.addListener(onOutput);
                try { MachiningClock.advance(h.getLevel()); }
                finally { NeoForge.EVENT_BUS.unregister(onOutput); }
                h.assertTrue(callbacks.get() == 1, "recipe fires exactly once");
                if (fracture) h.assertTrue(h.getBlockState(leftPos.east()).isAir()
                    && left.getOrCreateNetwork() != right.getOrCreateNetwork(), "overloaded join fractures and splits the islands in the same tick");
                else h.assertTrue(left.getOrCreateNetwork() == right.getOrCreateNetwork(), "callback joins previously independent islands in the same tick");
                for (var be : List.of(a, b, left, right)) near(h, RotationMath.TICK_MINUTES, trace.time(be), "no lost or duplicate time across regrouping");
                near(h, momentum, InertiaRegistry.getInertia(left) * NetworkDynamics.preciseSpeed(left)
                    + InertiaRegistry.getInertia(right) * NetworkDynamics.preciseSpeed(right), "joined rotors conserve momentum");
            }
            h.succeed();
        });
    }
    private static void near(GameTestHelper h, double expected, double actual, String label) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= 1e-8, label + ": expected " + expected + ", got " + actual);
    }
}

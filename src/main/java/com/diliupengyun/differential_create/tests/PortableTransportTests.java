package com.diliupengyun.differential_create.tests;

import java.util.*;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.kinetics.portable.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.gametest.*;

/** Native position programs and actual redstone start/continue pulses; all poses come from server physics. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class PortableTransportTests {
    private PortableTransportTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        for (boolean fluid : new boolean[]{false, true}) for (boolean piston : new boolean[]{false, true}) {
            String name = "dock_program_" + (piston ? "piston_" : "bearing_") + (fluid ? "fluid" : "item");
            if (name.matches(System.getProperty("differentialcreate.motion.select", ".*")))
                result.add(new TestFunction(name, name, "differentialcreate_motion:kineticphysicstests.physics_test", 60000, 0, true, h -> program(h, piston, fluid)));
        }
        return result;
    }
    private static void program(GameTestHelper h, boolean piston, boolean fluid) {
        var base = new BlockPos(9, 8, 12); var seqPos = base.below(); var input = seqPos.south();
        h.setBlock(base, (piston ? AllBlocks.MECHANICAL_PISTON : AllBlocks.MECHANICAL_BEARING).getDefaultState().setValue(BlockStateProperties.FACING, piston ? Direction.EAST : Direction.UP));
        h.setBlock(seqPos, AllBlocks.SEQUENCED_GEARSHIFT.getDefaultState().setValue(SequencedGearshiftBlock.VERTICAL, true));
        h.setBlock(base.below(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        CreativeMotorBlockEntity initialMotor = h.getBlockEntity(base.below(2)); initialMotor.generatedSpeed.setValue(0);
        if (piston) for (int x = 1; x <= 8; x++) h.setBlock(base.west(x), AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        var anchor = piston ? base.east() : base.above();
        h.setBlock(anchor, Blocks.SLIME_BLOCK);
        h.setBlock(piston ? anchor.above() : anchor.east(), Blocks.SLIME_BLOCK);
        var moving = piston ? anchor.above(2) : anchor.east(2);
        var port = fluid ? AllBlocks.PORTABLE_FLUID_INTERFACE : AllBlocks.PORTABLE_STORAGE_INTERFACE;
        h.setBlock(moving, port.getDefaultState().setValue(BlockStateProperties.FACING, piston ? Direction.NORTH : Direction.EAST));
        h.setBlock(piston ? anchor.south() : anchor.north(), fluid ? AllBlocks.FLUID_TANK.getDefaultState() : Blocks.CHEST.defaultBlockState());
        h.runAfterDelay(5, () -> {
            SequencedGearshiftBlockEntity seq = h.getBlockEntity(seqPos);
            CreativeMotorBlockEntity motor = h.getBlockEntity(base.below(2)); motor.generatedSpeed.setValue(piston ? -32 : 32);
            if (piston) {
                seq.getInstructions().clear(); seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_DISTANCE, 2));
                seq.getInstructions().add(new Instruction(SequencerInstructions.END));
                h.setBlock(input, Blocks.REDSTONE_BLOCK); h.runAfterDelay(2, () -> h.setBlock(input, Blocks.AIR));
            } else ((MechanicalBearingBlockEntity) h.getBlockEntity(base)).assemble();
        });
        h.runAfterDelay(12, () -> {
            SequencedGearshiftBlockEntity seq = h.getBlockEntity(seqPos);
            ControlledContraptionEntity root = piston ? (ControlledContraptionEntity) ((LinearActuatorBlockEntity) h.getBlockEntity(base)).movedContraption
                : ((MechanicalBearingBlockEntity) h.getBlockEntity(base)).getMovedContraption();
            h.assertTrue(root != null, "programmed controller assembles its storage body");
            if (fluid) root.getContraption().getStorage().getFluids().fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
            class Loop {
                int ticks, pulseEnd = -1, restartAt = -1, cycles, transfers;
                boolean priming = piston, primeStarted = piston, waitingConnected;
                final List<BlockPos> targets = new ArrayList<>();
                long generation = -1;
                void pulse() { h.setBlock(input, Blocks.REDSTONE_BLOCK); pulseEnd = ticks + 2; }
                void setupPorts() {
                    var ctx = root.getContraption().getActors().stream().filter(a -> a.getLeft().state().is(port.get())).findFirst().orElseThrow().getRight();
                    var body = root.toGlobalVector(Vec3.atCenterOf(ctx.localPos), 1);
                    var normal = root.applyRotation(Vec3.atLowerCornerOf(ctx.state.getValue(BlockStateProperties.FACING).getNormal()), 1);
                    var direction = Direction.getNearest(normal.x, normal.y, normal.z);
                    BlockPos near = BlockPos.containing(body).relative(direction, 2);
                    targets.add(piston ? near.east(4) : near); if (piston) targets.add(near);
                    for (var target : targets) {
                        h.getLevel().setBlockAndUpdate(target, port.getDefaultState().setValue(BlockStateProperties.FACING, direction.getOpposite()));
                        h.getLevel().setBlockAndUpdate(target.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    }
                }
                void fullProgram() {
                    seq.getInstructions().clear();
                    seq.getInstructions().add(new Instruction(piston ? SequencerInstructions.TURN_DISTANCE : SequencerInstructions.TURN_ANGLE, piston ? 4 : 360));
                    seq.getInstructions().add(new Instruction(SequencerInstructions.AWAIT));
                    if (piston) {
                        seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_DISTANCE, InstructionSpeedModifiers.BACK, 4));
                        seq.getInstructions().add(new Instruction(SequencerInstructions.AWAIT));
                    }
                    seq.getInstructions().add(new Instruction(SequencerInstructions.END));
                }
                void tick() {
                    ticks++;
                    if (ticks == pulseEnd) h.setBlock(input, Blocks.AIR);
                    var motion = ((ISequencerDynamics) seq).differentialcreate$motion();
                    if (priming) {
                        if (!primeStarted) {
                            primeStarted = true; seq.getInstructions().clear(); seq.getInstructions().add(new Instruction(SequencerInstructions.TURN_DISTANCE, 2));
                            seq.getInstructions().add(new Instruction(SequencerInstructions.END)); pulse();
                        } else if (motion.index < 0 && ticks > 10 && pulseEnd < ticks) {
                            double offset = ((LinearActuatorBlockEntity) h.getBlockEntity(base)).offset;
                            h.assertTrue(offset > 1 && offset < 3, "priming program parks inside the travel range: " + offset);
                            priming = false; setupPorts(); fullProgram(); restartAt = ticks + 8;
                        }
                    } else {
                        if (targets.isEmpty()) { setupPorts(); fullProgram(); restartAt = ticks + 8; }
                        if (ticks == restartAt && motion.index < 0) pulse();
                        int station = motion.index == 1 ? 0 : piston && motion.index == 3 ? 1 : -1;
                        for (int i = 0; i < targets.size(); i++) {
                            var target = targets.get(i);
                            var powered = h.getLevel().getBlockState(target.above()).is(Blocks.REDSTONE_BLOCK);
                            if (powered == (i == station)) h.getLevel().setBlockAndUpdate(target.above(), (i == station ? Blocks.AIR : Blocks.REDSTONE_BLOCK).defaultBlockState());
                        }
                        if (station >= 0) {
                            var target = targets.get(station);
                            var owner = (PortableStorageInterfaceBlockEntity) h.getLevel().getBlockEntity(target);
                            var state = ((PortableEndpoint) owner).differentialcreate$docking();
                            if (state.phase == DockingState.Phase.TRANSFER && state.session().generation() != generation) {
                                generation = state.session().generation(); waitingConnected = true; transfers++;
                                h.assertTrue(motion.settled() && !root.isStalled(), "position program is physically settled before storage opens");
                                if (fluid) h.assertTrue(h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, null).drain(1, FluidAction.EXECUTE).getAmount() == 1, "every programmed arrival transfers real fluid");
                                else h.assertTrue(ItemHandlerHelper.insertItem(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, target, null), new ItemStack(Items.STONE), false).isEmpty(), "every programmed arrival transfers real items");
                            }
                            if (waitingConnected && state.phase == DockingState.Phase.IDLE && ticks > pulseEnd) {
                                waitingConnected = false; generation = -1; pulse();
                                if (!piston || station == 1) {
                                    cycles++; restartAt = ticks + 8;
                                    if (cycles >= 100) {
                                        h.assertTrue(transfers == (piston ? 200 : 100), "all complete trips have one transfer at each intended station");
                                        if (fluid) h.assertTrue(root.getContraption().getStorage().getFluids().getFluidInTank(0).getAmount() == 4000 - transfers, "programmed fluid trips conserve mounted stock");
                                        root.discard(); h.succeed(); return;
                                    }
                                }
                            }
                        } else waitingConnected = false;
                    }
                    if (ticks > 58000 || ticks > 1400 && cycles == 0 && !priming) h.fail("program failed to repeat: piston=" + piston + ", cycles=" + cycles + ", transfers=" + transfers + ", motion=" + motion.index + "/" + motion.angle + "/" + motion.speed);
                    h.runAfterDelay(1, this::tick);
                }
            }
            h.runAfterDelay(1, new Loop()::tick);
        });
    }
}

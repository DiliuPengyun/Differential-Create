package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DifferentialCreate.MODID)
@PrefixGameTestTemplate(false)
public final class PumpPhysicsTests {
    private static final String TEMPLATE = "kineticphysicstests.physics_test";
    private static final BlockPos PUMP = new BlockPos(8, 3, 8);
    private static final BlockPos INLET = PUMP.west(5), BOILER = PUMP.east(5);
    private static final BlockPos DRIVE_SHAFT = PUMP.above().west();

    private record Fixture(GameTestHelper h, PumpBlockEntity pump, CreativeMotorBlockEntity motor,
                           FluidTankBlockEntity inlet, FluidTankBlockEntity boiler) {
        int water() { return inlet.getTankInventory().getFluidAmount(); }
        void speed(double motorRpm) {
            NetworkTopology.flush();
            var network = pump.getOrCreateNetwork();
            var ratios = ((INetworkDynamics) network).differentialcreate$getRatios();
            float reference = ratios.get(motor);
            ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = motorRpm * ratio / reference);
        }
    }

    private static Fixture fixture(GameTestHelper h, boolean flywheel) {
        return fixture(h, flywheel, true);
    }

    private static Fixture fixture(GameTestHelper h, boolean flywheel, boolean boiler) {
        var bounds = h.getBounds();
        for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
            for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
                h.getLevel().setChunkForced(x, z, true);
        h.setBlock(INLET, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(INLET.above(), AllBlocks.FLUID_TANK.getDefaultState());
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
        if (boiler) h.setBlock(BOILER.south(2), AllBlocks.STEAM_ENGINE.getDefaultState()
            .setValue(SteamEngineBlock.FACE, AttachFace.WALL).setValue(SteamEngineBlock.FACING, Direction.SOUTH));
        for (int x = -4; x <= 4; x++) if (x != 0) pipe(h, PUMP.east(x), Direction.WEST, Direction.EAST);
        h.setBlock(PUMP, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        h.setBlock(PUMP.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(PUMP.above().east(), (flywheel ? AllBlocks.FLYWHEEL : AllBlocks.SHAFT).getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(DRIVE_SHAFT, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(DRIVE_SHAFT.west(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = h.getBlockEntity(DRIVE_SHAFT.west());
        motor.generatedSpeed.setValue(0);
        Fixture f = new Fixture(h, h.getBlockEntity(PUMP), motor, h.getBlockEntity(INLET), h.getBlockEntity(BOILER));
        h.onEachTick(() -> {
            f.boiler.boiler.activeHeat = 2;
            f.boiler.boiler.passiveHeat = false;
            f.boiler.boiler.needsHeatLevelUpdate = false;
        });
        h.runAfterDelay(10, () -> {
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 16000), FluidAction.EXECUTE);
            f.speed(128);
            motor.generatedSpeed.setValue(64);
        });
        return f;
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 280)
    public static void coastingPumpFeedsBoiler(GameTestHelper h) {
        coasting(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 280)
    public static void flywheelPumpFeedsBoiler(GameTestHelper h) {
        coasting(h, true);
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 160)
    public static void reloadedPipeTracksCoastingPump(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(80, () -> {
            h.setBlock(DRIVE_SHAFT, Blocks.AIR);
            NetworkTopology.flush();
            var original = h.getBlockEntity(PUMP.west(2));
            var saved = original.saveWithoutMetadata(h.getLevel().registryAccess());
            var restored = original.getType().create(original.getBlockPos(), original.getBlockState());
            restored.loadWithComponents(saved, h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(original.getBlockPos());
            h.getLevel().setBlockEntity(restored);
            int before = f.water();
            h.runAfterDelay(45, () -> {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), restored.getBlockPos());
                double flow = FluidKinetics.state(pipe).outward.getOrDefault(Direction.EAST, 0d);
                h.assertTrue(flow > 10, "reloaded pipe restores real moving-column flow: " + flow);
                h.assertTrue(before - f.water() > 500, "water transfer resumes after pipe reload");
                h.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 300)
    public static void changingSpeedAndPipeTopology(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(80, () -> {
            f.motor.generatedSpeed.setValue(32);
            f.speed(64);
            int before = f.water(); double total = stored(f);
            h.runAfterDelay(20, () -> {
                int transferred = before - f.water();
                h.assertTrue(transferred > 300 && transferred < 1000, "flow settles after changing shaft speed: " + transferred);
                h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "source, destination and owned fractional fluid conserve water");
                BlockPos pipePos = PUMP.east(2);
                var pipe = h.getBlockState(pipePos);
                h.setBlock(pipePos, Blocks.STONE);
                h.runAfterDelay(10, () -> {
                    int blocked = f.water();
                    h.runAfterDelay(15, () -> {
                        h.assertTrue(f.water() == blocked, "blocked pipe stops transfer despite a running pump");
                        h.setBlock(pipePos, pipe);
                        h.runAfterDelay(35, () -> {
                            h.assertTrue(blocked - f.water() > 500, "replacing the pipe restores flow at unchanged pump speed");
                            h.setBlock(PUMP, h.getBlockState(PUMP).setValue(PumpBlock.FACING, Direction.WEST));
                            h.runAfterDelay(20, () -> {
                                int reverseStart = f.water();
                                h.runAfterDelay(20, () -> {
                                    h.assertTrue(f.water() > reverseStart + 500, "flipping the pump reverses real fluid transfer");
                                    h.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 180)
    public static void unequalSeriesPumpsExchangeEnergy(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        BlockPos secondPos = PUMP.east(2);
        h.setBlock(secondPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        h.setBlock(secondPos.below(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(secondPos.below().east(), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        CreativeMotorBlockEntity secondMotor = h.getBlockEntity(secondPos.below().east());
        PumpBlockEntity secondPump = h.getBlockEntity(secondPos);
        secondMotor.generatedSpeed.setValue(0);
        h.runAfterDelay(10, () -> setSecondSpeed(secondMotor, 64));
        h.runAfterDelay(80, () -> {
            h.assertTrue(Math.abs(Math.abs(secondPump.getSpeed()) - 64) < 0.1, "second drive maintains 64 RPM");
            assertSeriesFlow(h, f, secondPump);
            h.assertTrue(((IPumpDynamics) secondPump).differentialcreate$head() < 0,
                "faster water returns energy through the slower series pump");
            setSecondSpeed(secondMotor, 32);
            h.runAfterDelay(10, () -> {
                assertSeriesFlow(h, f, secondPump);
                h.assertTrue(Math.abs(f.pump.getSpeed()) > 127, "the first motor still supplies its controlled speed");
                h.succeed();
            });
        });
    }

    private static void setSecondSpeed(CreativeMotorBlockEntity motor, double rpm) {
        NetworkTopology.flush();
        var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
        float reference = ratios.get(motor);
        ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = rpm * ratio / reference);
        // This motor faces west, so a negative setting drives the positive seeded axis speed.
        motor.generatedSpeed.setValue(-(int) rpm / 2);
    }

    private static void assertSeriesFlow(GameTestHelper h, Fixture f, PumpBlockEntity second) {
        double firstFlow = FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow;
        double secondFlow = FluidKinetics.state(second.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow;
        h.assertTrue(firstFlow > 10 && Math.abs(firstFlow - secondFlow) < 1e-7,
            "series pumps share one conserved flow: " + firstFlow + " / " + secondFlow);
    }
    private static double stored(Fixture f) {
        double total = f.water() + f.boiler.getTankInventory().getFluidAmount();
        for (int x = -4; x <= 4; x++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.east(x)));
            if (pipe != null) for (var buffer : FluidKinetics.state(pipe).buffers.values()) total += buffer.amount;
        }
        return total;
    }

    private static void coasting(GameTestHelper h, boolean flywheel) {
        Fixture f = fixture(h, flywheel);
        h.runAfterDelay(80, () -> {
            h.assertTrue(f.boiler.boiler.waterSupply > 20, "powered pump supplies water to the real boiler");
            h.assertTrue(f.water() < 15000, "powered pump drains its input tank");
            h.setBlock(DRIVE_SHAFT, Blocks.AIR);
            NetworkTopology.flush();
            h.assertTrue(f.pump.getOrCreateNetwork().sources.isEmpty(), "pump has physically disconnected from its source");
            int before = f.water();
            float speed = Math.abs(f.pump.getSpeed());
            h.runAfterDelay(45, () -> {
                float after = Math.abs(f.pump.getSpeed());
                DifferentialCreate.LOGGER.info("PUMP_COAST beforeWater={} afterWater={} beforeRpm={} afterRpm={} boilerWater={} pumpPhase={}",
                    before, f.water(), speed, after, f.boiler.boiler.waterSupply,
                    f.pump.getBehaviour(FluidTransportBehaviour.TYPE).phase);
                h.assertTrue(after > 90 && after < speed, "disconnected pump still rotates and spends stored rotational energy");
                h.assertTrue(before - f.water() > 500, "coasting pump continues transporting water while its speed changes");
                h.runAfterDelay(70, () -> {
                    h.assertTrue(f.boiler.boiler.waterSupply > 10, "boiler water survives beyond the previous supply sampling window");
                    var network = f.pump.getOrCreateNetwork();
                    network.members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
                    h.setBlock(PUMP.east(2), Blocks.STONE);
                    h.runAfterDelay(10, () -> {
                        int stopped = f.water();
                        h.runAfterDelay(20, () -> {
                            h.assertTrue(stopped == f.water(), "a blocked path cannot keep transporting cached flow");
                            h.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 150)
    public static void movingWaterBackdrivesStoppedPump(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(80, () -> {
            h.setBlock(DRIVE_SHAFT, Blocks.AIR); NetworkTopology.flush();
            f.pump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
            int output = f.boiler.getTankInventory().getFluidAmount(); double total = stored(f);
            h.runAfterDelay(4, () -> {
                h.assertTrue(Math.abs(f.pump.getSpeed()) > .1, "column kinetic energy starts the stopped, disconnected rotor");
                h.assertTrue(f.boiler.getTankInventory().getFluidAmount() > output, "moving water continues after shaft stop");
                h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "backdrive conserves actual fluid ownership");
                h.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 160)
    public static void fullOriginalTankStopsFlow(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(60, () -> {
            f.boiler.getTankInventory().fill(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), FluidAction.EXECUTE);
            double total = stored(f);
            h.runAfterDelay(10, () -> {
                int before = f.water();
                h.runAfterDelay(20, () -> {
                    h.assertTrue(f.water() == before, "original tank's rejection stops upstream extraction");
                    h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "full destination does not delete pending fluid");
                    h.assertTrue(Math.abs(f.pump.getSpeed()) > 100, "ideal blocked pump has no invented friction load");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 180)
    public static void lowSpeedHasNoFreeMinimumFlow(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        boolean[] controlled = {false};
        h.onEachTick(() -> {
            if (!controlled[0] || f.pump.isRemoved()) return;
            f.pump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = -.5);
        });
        h.runAfterDelay(50, () -> {
            h.setBlock(DRIVE_SHAFT, Blocks.AIR); NetworkTopology.flush();
            f.pump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
            for (int x = -4; x <= 4; x++) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(x)));
                if (pipe != null) { FluidKinetics.state(pipe).outward.replaceAll((side, flow) -> 0d); FluidKinetics.state(pipe).pumpFlow = 0; }
            }
            controlled[0] = true;
            int output = f.boiler.getTankInventory().getFluidAmount(); double total = stored(f);
            h.runAfterDelay(80, () -> {
                int supplied = f.boiler.getTankInventory().getFluidAmount() - output;
                h.assertTrue(supplied >= 0 && supplied < 10, "controlled half-RPM does not deliver one free mB each tick: " + supplied);
                h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "fractional low-speed transfer preserves real inventory");
                h.succeed();
            });
        });
    }

    private static void pipe(GameTestHelper h, BlockPos pos, Direction... sides) {
        var state = AllBlocks.FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), false);
        for (var side : sides) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), true);
        h.setBlock(pos, state);
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 130)
    public static void glassConversionPreservesOwnedFluid(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(80, () -> {
            BlockPos relative = PUMP.west(4), absolute = h.absolutePos(relative);
            double total = stored(f); int before = f.water();
            FluidTransportBehaviour.cacheFlows(h.getLevel(), absolute);
            h.setBlock(relative, AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            FluidTransportBehaviour.loadFlows(h.getLevel(), absolute);
            h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "wrench conversion transfers actual owned fluid to the replacement pipe");
            h.runAfterDelay(20, () -> {
                h.assertTrue(before - f.water() > 500, "converted glass pipe continues actual transfer");
                h.assertTrue(Math.abs(stored(f) - total) < 1e-6, "conversion neither drops nor duplicates fractional inventory");
                h.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 100)
    public static void emptySourceStopsWithoutLosingFractions(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        double[] total = {0};
        h.runAfterDelay(11, () -> {
            f.inlet.getTankInventory().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 120), FluidAction.EXECUTE);
            total[0] = stored(f);
        });
        h.runAfterDelay(60, () -> {
            h.assertTrue(f.water() == 0, "finite original supply empties during a physical step");
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-6, "empty-source boundary preserves each owned fraction");
            h.assertTrue(Math.abs(FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow) < 1e-7,
                "empty source closes the pipe instead of replaying cached flow");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 90)
    public static void openEndKeepsOriginalBuffer(GameTestHelper h) {
        BlockPos pos = new BlockPos(6, 3, 8);
        h.setBlock(pos.west(), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, pos, Direction.WEST, Direction.EAST);
        int[] owned = {0};
        h.runAfterDelay(10, () -> {
            var behaviour = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) behaviour.getConnection(Direction.EAST)).differentialcreate$source();
            h.assertTrue(source instanceof com.simibubi.create.content.fluids.OpenEndedPipe, "fixture uses original open-end capability");
            ((net.neoforged.neoforge.fluids.capability.templates.FluidTank) source.provideHandler().getCapability()).setFluid(new FluidStack(Fluids.WATER, 100));
            owned[0] = source.provideHandler().getCapability().getFluidInTank(0).getAmount();
            h.assertTrue(owned[0] == 100, "fixture seeds the original open-end's stored inventory");
        });
        h.runAfterDelay(50, () -> {
            var behaviour = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) behaviour.getConnection(Direction.EAST)).differentialcreate$source();
            h.assertTrue(source.provideHandler().getCapability().getFluidInTank(0).getAmount() == owned[0],
                "graph captures retain the original open-end's actual inventory");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 130)
    public static void gravityStartsUnpoweredPump(GameTestHelper h) {
        BlockPos high = new BlockPos(6, 5, 8), low = new BlockPos(12, 1, 8);
        h.setBlock(high, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(low, AllBlocks.FLUID_TANK.getDefaultState());
        for (int y = 2; y < 5; y++) pipe(h, new BlockPos(6, y, 8), Direction.UP, Direction.DOWN);
        pipe(h, new BlockPos(6, 1, 8), Direction.UP, Direction.EAST);
        for (int x = 7; x < 12; x++) if (x != 9) pipe(h, new BlockPos(x, 1, 8), Direction.WEST, Direction.EAST);
        BlockPos pumpPos = new BlockPos(9, 1, 8);
        h.setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        FluidTankBlockEntity source = h.getBlockEntity(high), destination = h.getBlockEntity(low);
        PumpBlockEntity pump = h.getBlockEntity(pumpPos);
        int[] supplied = {0};
        h.runAfterDelay(10, () -> {
            supplied[0] = source.getTankInventory().fill(new FluidStack(Fluids.WATER, 6000), FluidAction.EXECUTE);
            h.assertTrue(supplied[0] > 0 && source.getTankInventory().getFluidAmount() == supplied[0], "gravity source owns its accepted inventory");
        });
        h.runAfterDelay(80, () -> {
            h.assertTrue(destination.getTankInventory().getFluidAmount() > 200, "height difference transports water without a motor");
            h.assertTrue(Math.abs(pump.getSpeed()) > .1, "gravity-fed water backdrives the real pump");
            double total = source.getTankInventory().getFluidAmount() + destination.getTankInventory().getFluidAmount() + localOwned(h);
            h.assertTrue(Math.abs(total - supplied[0]) < 1e-6, "gravity transfer conserves original tank inventory and fractions: expected=" + supplied[0] + "; actual="
                + total + "; source=" + source.getTankInventory().getFluidAmount() + "; destination=" + destination.getTankInventory().getFluidAmount() + "; owned=" + localOwned(h));
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump", timeoutTicks = 130)
    public static void sameTankPortsCannotDrivePump(GameTestHelper h) {
        BlockPos tankPos = new BlockPos(6, 3, 8), pumpPos = new BlockPos(7, 5, 8);
        for (int y = 0; y < 3; y++) h.setBlock(tankPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, new BlockPos(7, 3, 8), Direction.WEST, Direction.EAST);
        pipe(h, new BlockPos(8, 3, 8), Direction.WEST, Direction.UP);
        pipe(h, new BlockPos(8, 4, 8), Direction.DOWN, Direction.UP);
        pipe(h, new BlockPos(8, 5, 8), Direction.DOWN, Direction.WEST);
        h.setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        FluidTankBlockEntity tank = h.getBlockEntity(tankPos); PumpBlockEntity pump = h.getBlockEntity(pumpPos);
        h.runAfterDelay(10, () -> tank.getTankInventory().fill(new FluidStack(Fluids.WATER, 12000), FluidAction.EXECUTE));
        h.runAfterDelay(80, () -> {
            h.assertTrue(tank.getTankInventory().getFluidAmount() == 12000 && localOwned(h) < 1e-8,
                "upper and lower ports of one original tank do not create circulation");
            h.assertTrue(Math.abs(pump.getSpeed()) < 1e-7, "same-tank height difference supplies no free shaft energy");
            h.assertTrue(Math.abs(FluidKinetics.state(pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow) < 1e-7,
                "all same-tank ports use the controller's common pressure reference");
            h.succeed();
        });
    }

    private static double localOwned(GameTestHelper h) {
        double amount = 0;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 10; y++) for (int z = 0; z < 16; z++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(new BlockPos(x, y, z)));
            if (pipe != null) for (var buffer : FluidKinetics.state(pipe).buffers.values()) amount += buffer.amount;
        }
        return amount;
    }
}

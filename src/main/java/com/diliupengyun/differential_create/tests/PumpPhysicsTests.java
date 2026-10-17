package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IPumpDynamics;
import com.diliupengyun.differential_create.kinetics.IdealFluidSolver;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkLoad;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.PipeFilling;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

    /** These fixtures seed initial liquid geometry through Contents/Flow. Discard
     * interval velocities from the prior, different physical initial state. */
    private static void setInitialContents(PipeHydraulics state) {
        state.phases = null;
        state.intervalFlows.clear(); state.coreFlows = new double[0];
        state.macroLiquid = Double.NaN;
    }

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
        return fixture(h, flywheel, boiler, false);
    }

    private static Fixture fixture(GameTestHelper h, boolean flywheel, boolean boiler, boolean bottomSource) {
        forceChunks(h);
        BlockPos sourcePos = bottomSource ? PUMP.west(4).above() : INLET;
        h.setBlock(sourcePos, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(sourcePos.above(), AllBlocks.FLUID_TANK.getDefaultState());
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
        if (boiler) h.setBlock(BOILER.south(2), AllBlocks.STEAM_ENGINE.getDefaultState()
            .setValue(SteamEngineBlock.FACE, AttachFace.WALL).setValue(SteamEngineBlock.FACING, Direction.SOUTH));
        if (boiler) {
            h.setBlock(BOILER.south(4), AllBlocks.POWERED_SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            h.setBlock(BOILER.south(4).east(), AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        }
        for (int x = -4; x <= 4; x++) if (x != 0)
            pipe(h, PUMP.east(x), bottomSource && x == -4 ? Direction.UP : Direction.WEST, Direction.EAST);
        h.setBlock(PUMP, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        h.setBlock(PUMP.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(PUMP.above().east(), (flywheel ? AllBlocks.FLYWHEEL : AllBlocks.SHAFT).getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(DRIVE_SHAFT, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(DRIVE_SHAFT.west(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = h.getBlockEntity(DRIVE_SHAFT.west());
        motor.generatedSpeed.setValue(0);
        Fixture f = new Fixture(h, h.getBlockEntity(PUMP), motor, h.getBlockEntity(sourcePos), h.getBlockEntity(BOILER));
        h.onEachTick(() -> {
            f.boiler.boiler.activeHeat = 2;
            f.boiler.boiler.passiveHeat = false;
            f.boiler.boiler.needsHeatLevelUpdate = false;
        });
        h.runAfterDelay(10, () -> {
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 16000), FluidAction.EXECUTE);
            motor.generatedSpeed.setValue(-64);
            f.speed(SourcePhysics.curve(motor).noLoadSpeed());
        });
        return f;
    }

    private static void forceChunks(GameTestHelper h) {
        var bounds = h.getBounds();
        for (int x = ((int) bounds.minX >> 4) - 1; x <= ((int) bounds.maxX >> 4) + 1; x++)
            for (int z = ((int) bounds.minZ >> 4) - 1; z <= ((int) bounds.maxZ >> 4) + 1; z++)
                h.getLevel().setChunkForced(x, z, true);
    }

    @GameTest(template = TEMPLATE, batch = "pump_coastingPumpFeedsBoiler", timeoutTicks = 280)
    public static void coastingPumpFeedsBoiler(GameTestHelper h) {
        coasting(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "pump_flywheelPumpFeedsBoiler", timeoutTicks = 280)
    public static void flywheelPumpFeedsBoiler(GameTestHelper h) {
        coasting(h, true);
    }

    @GameTest(template = TEMPLATE, batch = "pump_reloadedPipeTracksCoastingPump", timeoutTicks = 160)
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

    @GameTest(template = TEMPLATE, batch = "pump_changingSpeedAndPipeTopology", timeoutTicks = 300)
    public static void changingSpeedAndPipeTopology(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
        h.runAfterDelay(80, () -> {
            f.motor.generatedSpeed.setValue(-32);
            f.speed(SourcePhysics.curve(f.motor).noLoadSpeed());
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
                            balanceFixtureTanks(f);
                            f.motor.generatedSpeed.setValue(32);
                            h.runAfterDelay(30, () -> {
                                int reverseStart = f.water();
                                double reverseTotal = stored(f);
                                h.runAfterDelay(20, () -> {
                                    h.assertTrue(f.water() > reverseStart + 300,
                                        "reversing the actual shaft reverses sustained fluid transfer with native boundary momentum loss");
                                    h.assertTrue(Math.abs(stored(f) - reverseTotal) < 1e-6,
                                        "reversed transfer preserves original and owned inventory");
                                    h.succeed();
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_unequalSeriesPumpsExchangeEnergy", timeoutTicks = 180)
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
        h.runAfterDelay(11, () -> primeFixturePipes(f));
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
        ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = -rpm * ratio / reference);
        // West-facing motor plus the real meshing cog give a positive pump
        // axis from a positive control setting. Seed the same physical state.
        motor.generatedSpeed.setValue((int) rpm / 2);
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
            if (pipe != null) {
                total += FluidKinetics.state(pipe).volume();
                for (var buffer : FluidKinetics.state(pipe).buffers.values()) total += buffer.amount;
            }
        }
        return total;
    }
    /** Shaft/readout/through-flow regressions need a full, already primed path.
     * Partial ordinary pipes now accumulate real stock during startup, so two
     * series devices need not have equal instantaneous flow while filling.
     * Withdraw all priming water from the native source and keep its rounding
     * surplus owned; dedicated filling tests still begin with dry pipes. */
    private static void primeFixturePipes(Fixture f) {
        double missing = 0;
        for (int x = -4; x <= 4; x++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.east(x)));
            missing += Math.max(0, 100 - FluidKinetics.state(pipe).volume());
        }
        int requested = (int) Math.ceil(missing);
        int reserved = f.inlet.getTankInventory().drain(requested, FluidAction.EXECUTE).getAmount();
        f.h.assertTrue(reserved == requested, "the native source supplies all real priming water");
        for (int x = -4; x <= 4; x++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.east(x)));
            var saved = FluidKinetics.state(pipe); saved.fluid = new FluidStack(Fluids.WATER, 1); saved.outward.clear(); saved.pumpFlow = 0;
            saved.adoptMacro(100, false, pipe.interfaces.keySet(), .005);
        }
        var inletPipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.west(4)));
        FluidKinetics.state(inletPipe).buffers.computeIfAbsent(Direction.WEST, side -> new PipeHydraulics.Buffer())
            .add(new FluidStack(Fluids.WATER, 1), reserved - missing);
        FluidKinetics.invalidate(f.h.getLevel());
    }
    private static double closedUpstreamStored(Fixture f) {
        double total = f.water();
        for (int x = -4; x <= 1; x++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.east(x)));
            var state = FluidKinetics.state(pipe);
            total += state.volume() + state.buffers.values().stream().mapToDouble(b -> b.amount).sum();
        }
        return total;
    }

    private static void coasting(GameTestHelper h, boolean flywheel) {
        Fixture f = fixture(h, flywheel);
        h.runAfterDelay(80, () -> afterSupplyTick(f,
            "powered pump supplies only the real cylinder's bounded water demand", () -> {
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
                h.assertTrue(before > f.water(), "coasting pump continues paying real cylinder water demand while its speed changes");
                h.runAfterDelay(70, () -> afterSupplyTick(f,
                    "current pump delivery supplies the loaded engine without a historical sampling window", () -> {
                    var network = f.pump.getOrCreateNetwork();
                    network.members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
                    h.setBlock(PUMP.east(2), Blocks.STONE);
                    double closed = closedUpstreamStored(f);
                    // The six upstream pipe/pump cells each own 100 mB of
                    // capacity. Without an air spring, their remaining room
                    // may still receive water after the outlet is blocked.
                    int source = f.water();
                    double room = 600 - (closed - source);
                    h.runAfterDelay(10, () -> {
                        h.runAfterDelay(20, () -> {
                            h.assertTrue(Math.abs(closedUpstreamStored(f) - closed) < 1e-6,
                                "a blocked path retains source water in actual upstream inventory");
                            h.assertTrue(source - f.water() <= room + 1 + 1e-6,
                                "blocked-pipe admission is limited by real room and one source rounding buffer");
                            h.succeed();
                        });
                    });
                }));
            });
        }));
    }

    /** GameTest callbacks run before the Post tick physics step. Capture its
     * completed supply once, then assert inside GameTest on the following tick;
     * reading the live boiler there would again see only prepaid fractions. */
    private static void afterSupplyTick(Fixture f, String message, Runnable after) {
        long requestedTick = f.h.getLevel().getGameTime();
        class SupplySample implements Consumer<ServerTickEvent.Post> {
            long tick = Long.MIN_VALUE;
            double demand, delivered;

            @Override
            public void accept(ServerTickEvent.Post event) {
                if (event.getServer() != f.h.getLevel().getServer()) return;
                NeoForge.EVENT_BUS.unregister(this);
                tick = f.h.getLevel().getGameTime();
                demand = BoilerAdmission.demand(f.boiler.boiler);
                delivered = f.boiler.boiler.waterSupply;
            }
        }
        var sample = new SupplySample();
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class, sample);
        f.h.runAfterDelay(1, () -> {
            f.h.assertTrue(sample.tick == requestedTick, "supply snapshot belongs to the requested completed physics tick");
            f.h.assertTrue(sample.demand > 0 && sample.demand <= 10
                && sample.delivered > 0 && sample.delivered <= 10 && sample.delivered <= sample.demand + 1e-6,
                message + ": demand=" + sample.demand + " delivered=" + sample.delivered);
            after.run();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_movingWaterBackdrivesStoppedPump", timeoutTicks = 150)
    public static void movingWaterBackdrivesStoppedPump(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
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

    @GameTest(template = TEMPLATE, batch = "pump_fullOriginalTankStopsFlow", timeoutTicks = 160)
    public static void fullOriginalTankStopsFlow(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
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

    @GameTest(template = TEMPLATE, batch = "pump_blockedPumpLoadReadout", timeoutTicks = 160)
    public static void blockedPumpLoadReadout(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
        h.runAfterDelay(50, () -> {
            double input = SourcePhysics.curve(f.motor).torque(NetworkDynamics.preciseSpeed(f.motor))
                * NetworkDynamics.preciseSpeed(f.motor);
            double demand = ((IPumpDynamics) f.pump).differentialcreate$torque() * NetworkDynamics.preciseSpeed(f.pump);
            var reading = NetworkLoad.of(f.motor);
            h.assertTrue(input > 0 && demand > 0 && reading.status() == LoadFactor.Status.AVAILABLE
                && Math.abs(reading.fraction() - demand / input) < 1e-10,
                "a running pump retains its actual shaft demand and source supply: " + reading);
        });
        h.runAfterDelay(60, () -> {
            f.boiler.getTankInventory().fill(new FluidStack(Fluids.WATER, Integer.MAX_VALUE), FluidAction.EXECUTE);
            f.motor.generatedSpeed.setValue(-32);
            f.speed(SourcePhysics.curve(f.motor).noLoadSpeed());
        });
        h.runAfterDelay(100, () -> {
            // Reproduce the saved 64-RPM blocked-pump state: two speed ulps of
            // overshoot and a pressure-projection residual of about 1e-15 mB/tick.
            for (int direction : new int[]{1, -1}) for (int ulps : new int[]{-2, 0, 2}) for (int flowSign : new int[]{-1, 1}) {
                f.motor.generatedSpeed.setValue(direction * 32);
                double target = SourcePhysics.curve(f.motor).noLoadSpeed();
                f.speed(target + Math.copySign(Math.ulp(target), target) * ulps);
                double rpm = NetworkDynamics.preciseSpeed(f.pump);
                double flow = flowSign * Math.ulp(8d);
                double torque = IdealFluidSolver.coupling(new IdealFluidSolver.Material(1000, false), rpm, flow) * flow;
                ((IPumpDynamics) f.pump).differentialcreate$hydraulicState(16, flow, torque);
                assertZeroPumpLoad(h, f);
                LoadFactorTests.tooltip(h, f.motor, "0%", net.minecraft.ChatFormatting.GREEN);
            }
            double stoppedTotal = stored(f);
            h.onEachTick(() -> {
                if (h.getTick() > 100 && h.getTick() < 130) assertZeroPumpLoad(h, f);
            });
            h.runAfterDelay(30, () -> {
                assertZeroPumpLoad(h, f);
                h.assertTrue(f.boiler.getTankInventory().getFluidAmount() == f.boiler.getTankInventory().getCapacity()
                    && Math.abs(FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow) < 1e-9,
                    "readout correction cannot restart through-flow into a full receiver");
                h.assertTrue(Math.abs(stored(f) - stoppedTotal) < 1e-6,
                    "passive filling of remaining pipe space keeps each source debit owned");
                h.assertTrue(Math.abs(f.motor.getSpeed()) == 64, "readout correction preserves the physical motor speed");
                f.motor.generatedSpeed.setValue(0);
                NetworkTopology.flush();
                h.assertTrue(NetworkLoad.of(f.motor).status() == LoadFactor.Status.NO_POWER,
                    "roundoff in a blocked pump cannot masquerade as an active source after the motor shuts off");
                h.succeed();
            });
        });
    }

    private static void assertZeroPumpLoad(GameTestHelper h, Fixture f) {
        var reading = NetworkLoad.of(f.motor);
        h.assertTrue(reading.status() == LoadFactor.Status.AVAILABLE && reading.fraction() == 0,
            "blocked pump has zero load instead of dividing roundoff residues: " + reading
                + "; motor=" + NetworkDynamics.preciseSpeed(f.motor) + "; pumpTorque=" + ((IPumpDynamics) f.pump).differentialcreate$torque());
    }

    @GameTest(template = TEMPLATE, batch = "pump_lowSpeedHasNoFreeMinimumFlow", timeoutTicks = 180)
    public static void lowSpeedHasNoFreeMinimumFlow(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        boolean[] controlled = {false};
        h.onEachTick(() -> {
            if (!controlled[0] || f.pump.isRemoved()) return;
            f.pump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = .5);
        });
        h.runAfterDelay(50, () -> {
            h.setBlock(DRIVE_SHAFT, Blocks.AIR); NetworkTopology.flush();
            f.pump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
            for (int x = -4; x <= 4; x++) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(x)));
                if (pipe != null) { FluidKinetics.state(pipe).outward.replaceAll((side, flow) -> 0d); FluidKinetics.state(pipe).pumpFlow = 0; }
            }
            primeFixturePipes(f);
            balanceFixtureTanks(f);
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

    private static void balanceFixtureTanks(Fixture f) {
        // These regressions isolate shaft direction and sub-mB pumping, not
        // gravity-fed transfer caused by different original tank water levels.
        for (var tank : java.util.List.of(f.inlet, f.boiler)) {
            var stock = tank.getTankInventory();
            stock.setFluid(new FluidStack(Fluids.WATER, stock.getCapacity() / 2));
        }
        for (int x : new int[]{-4, 4}) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(f.h.getLevel(), f.h.absolutePos(PUMP.east(x)));
            for (var buffer : FluidKinetics.state(pipe).buffers.values()) { buffer.amount = 0; buffer.delivery = false; }
        }
    }

    private static void pipe(GameTestHelper h, BlockPos pos, Direction... sides) {
        var state = AllBlocks.FLUID_PIPE.getDefaultState();
        for (var side : Direction.values()) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), false);
        for (var side : sides) state = state.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(side), true);
        h.setBlock(pos, state);
    }

    @GameTest(template = TEMPLATE, batch = "pump_glassConversionPreservesOwnedFluid", timeoutTicks = 130)
    public static void glassConversionPreservesOwnedFluid(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
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

    @GameTest(template = TEMPLATE, batch = "pump_emptySourceStopsWithoutLosingFractions", timeoutTicks = 100)
    public static void emptySourceStopsWithoutLosingFractions(GameTestHelper h) {
        Fixture f = fixture(h, false, false, true);
        double[] total = {0};
        h.runAfterDelay(11, () -> {
            f.inlet.getTankInventory().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 120), FluidAction.EXECUTE);
            total[0] = stored(f);
        });
        h.runAfterDelay(60, () -> {
            h.assertTrue(f.water() == 0, "finite original supply empties during a physical step");
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-6, "empty-source boundary preserves each owned fraction");
        });
        h.runAfterDelay(80, () -> {
            h.assertTrue(f.water() == 0, "an exhausted original source stays empty");
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-6,
                "continued motion can only redistribute the actual remaining liquid");
            h.succeed();
        });
    }

    // This checks the exact stop level within a fixed transfer interval. Keep
    // it out of the concurrent pump stress batch, where the world's shared
    // fluid CPU budget may deliberately defer otherwise valid updates.
    @GameTest(template = TEMPLATE, batch = "pump_side_port_drainingTankStopsAtSidePort", timeoutTicks = 110)
    public static void drainingTankStopsAtSidePort(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        double[] total = {0}, owned = {0}; int[] stopped = {0}, received = {0};
        h.runAfterDelay(11, () -> {
            f.inlet.getTankInventory().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 4250), FluidAction.EXECUTE);
            for (int x = -4; x <= 4; x++) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(x)));
                var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = new FluidStack(Fluids.WATER, 1);
                if (pipe.blockEntity instanceof PumpBlockEntity p) {
                    saved.pumpFacing = p.getBlockState().getValue(PumpBlock.FACING);
                    saved.core = com.diliupengyun.differential_create.kinetics.PipeContents.FULL;
                }
                for (var side : pipe.interfaces.keySet()) if (pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side))
                    saved.contents.put(side, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
            }
            total[0] = stored(f); FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(60, () -> {
            double contact = f.inlet.getTankInventory().getCapacity() * (3.0 / 16) / (f.inlet.getHeight() - 9.0 / 16);
            h.assertTrue(f.water() >= Math.floor(contact) && f.water() <= Math.ceil(contact) + 1,
                "a side port stops draining at the tank surface, within the native integer inventory: " + f.water() + " / " + contact);
            stopped[0] = f.water();
            received[0] = f.boiler.getTankInventory().getFluidAmount();
            owned[0] = stored(f) - stopped[0] - received[0];
        });
        h.runAfterDelay(95, () -> {
            h.assertTrue(f.water() == stopped[0], "a running pump cannot continue draining below its dry side port");
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-6, "crossing the side port retains each actual fluid fraction");
            // Dry native stock stays closed, while previously owned pipe water
            // may still reach the outlet. The pump's total flow need not be zero.
            double delivery = f.boiler.getTankInventory().getFluidAmount() - received[0];
            h.assertTrue(delivery >= 0 && delivery <= owned[0] + 1e-6,
                "continued delivery after the side port dries uses only owned pipe liquid: " + delivery + " / " + owned[0]);
            h.assertTrue(Double.isFinite(FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow),
                "receding pipe liquid keeps a finite pump flow");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_nearlyFullReceiptSurvivesReload", timeoutTicks = 145)
    public static void nearlyFullReceiptSurvivesReload(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
        double[] total = {0};
        h.runAfterDelay(80, () -> {
            var target = f.boiler.getTankInventory();
            target.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            target.fill(new FluidStack(Fluids.WATER, target.getCapacity() - 1), FluidAction.EXECUTE);
            var outlet = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(4)));
            var buffer = FluidKinetics.state(outlet).buffers.computeIfAbsent(Direction.EAST,
                side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
            // Actual crash-save value: the receiver's final mB is already owned,
            // apart from floating-point roundoff, and cannot create a new time event.
            buffer.amount = .9999999999999992;
            buffer.fluid = new FluidStack(Fluids.WATER, 1); buffer.delivery = true;
            var original = outlet.blockEntity;
            var saved = original.saveWithoutMetadata(h.getLevel().registryAccess());
            var restored = original.getType().create(original.getBlockPos(), original.getBlockState());
            restored.loadWithComponents(saved, h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(original.getBlockPos()); h.getLevel().setBlockEntity(restored);
            total[0] = stored(f);
        });
        h.runAfterDelay(110, () -> {
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-8, "receipt roundoff must retain actual water ownership");
            h.assertTrue(Math.abs(FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow) < 1e-10,
                "roundoff-sized receiver space closes the wet mouth instead of shrinking the physical clock");
            var outlet = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(4)));
            h.assertTrue(f.boiler.getTankInventory().getFluidAmount() == f.boiler.getTankInventory().getCapacity()
                && FluidKinetics.state(outlet).buffers.get(Direction.EAST).amount == 0,
                "the last integer receipt completes once, within floating-point precision");
            f.boiler.getTankInventory().drain(4000, FluidAction.EXECUTE);
            total[0] -= 4000;
        });
        h.runAfterDelay(135, () -> {
            h.assertTrue(FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow > 1,
                "consuming real receiver stock reopens its wet mouth");
            h.assertTrue(Math.abs(stored(f) - total[0]) < 1e-8, "resumed receipt preserves the retained fraction");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_roundedReceiptReopensNativeOpenEnd", timeoutTicks = 95)
    public static void roundedReceiptReopensNativeOpenEnd(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        h.runAfterDelay(11, () -> primeFixturePipes(f));
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(BOILER.offset(x, y, z), Blocks.AIR);
        double[] total = {0};
        h.runAfterDelay(80, () -> {
            h.setBlock(BOILER, Blocks.AIR);
            var outlet = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(4)));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) outlet.getConnection(Direction.EAST)).differentialcreate$source();
            h.assertTrue(source instanceof com.simibubi.create.content.fluids.OpenEndedPipe, "fixture exercises the native open-end receipt limit");
            ((net.neoforged.neoforge.fluids.capability.templates.FluidTank) source.provideHandler().getCapability()).setFluid(FluidStack.EMPTY);
            ((com.diliupengyun.differential_create.kinetics.OpenPipeDischarge.Placement)
                source.provideHandler().getCapability()).differentialcreate$clearDischarge();
            var buffer = FluidKinetics.state(outlet).buffers.computeIfAbsent(Direction.EAST,
                side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
            buffer.amount = .9999999999999992; buffer.fluid = new FluidStack(Fluids.WATER, 1); buffer.delivery = true;
            FluidKinetics.invalidate(h.getLevel());
            total[0] = f.water() + localOwned(h);
        });
        h.runAfterDelay(85, () -> {
            var outlet = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(4)));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) outlet.getConnection(Direction.EAST)).differentialcreate$source();
            double received = discharged(h, (com.simibubi.create.content.fluids.OpenEndedPipe) source);
            h.assertTrue(received > 1 && FluidKinetics.state(f.pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow > 1,
                "the open end discharges the rounded integer and continues receiving, instead of staying blocked: " + received);
            h.assertTrue(Math.abs(f.water() + localOwned(h) + received - total[0]) < 1e-6,
                "real open-end discharge and fractional ownership account for all supplied water");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_openEndKeepsOriginalBuffer", timeoutTicks = 90)
    public static void openEndKeepsOriginalBuffer(GameTestHelper h) {
        BlockPos pos = new BlockPos(6, 3, 8);
        h.setBlock(pos.west(), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, pos, Direction.WEST, Direction.EAST);
        int[] owned = {0};
        net.neoforged.neoforge.fluids.capability.IFluidHandler[] original = {null};
        h.runAfterDelay(10, () -> {
            var behaviour = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) behaviour.getConnection(Direction.EAST)).differentialcreate$source();
            h.assertTrue(source instanceof com.simibubi.create.content.fluids.OpenEndedPipe, "fixture uses original open-end capability");
            ((net.neoforged.neoforge.fluids.capability.templates.FluidTank) source.provideHandler().getCapability()).setFluid(new FluidStack(Fluids.WATER, 100));
            owned[0] = source.provideHandler().getCapability().getFluidInTank(0).getAmount();
            original[0] = source.provideHandler().getCapability();
            h.assertTrue(owned[0] == 100, "fixture seeds the original open-end's stored inventory");
            FluidKinetics.invalidate(h.getLevel()); FluidKinetics.capture(h.getLevel());
            h.assertTrue(original[0].getFluidInTank(0).getAmount() == owned[0],
                "a topology capture cannot drain or recreate native open-end inventory");
        });
        h.runAfterDelay(50, () -> {
            var behaviour = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) behaviour.getConnection(Direction.EAST)).differentialcreate$source();
            FluidTankBlockEntity tank = h.getBlockEntity(pos.west());
            h.assertTrue(source.provideHandler().getCapability() == original[0],
                "graph captures retain the original open-end handler identity");
            h.assertTrue(Math.abs(original[0].getFluidInTank(0).getAmount() + pipeOwned(h, pos)
                + discharged(h, (com.simibubi.create.content.fluids.OpenEndedPipe) source)
                + tank.getTankInventory().getFluidAmount() - owned[0]) < 1e-6,
                "native actual stock remains owned until it is really drawn or discharged");
            h.succeed();
        });
    }

    private static double discharged(GameTestHelper h, com.simibubi.create.content.fluids.OpenEndedPipe pipe) {
        var registries = h.getLevel().registryAccess();
        var tag = pipe.serializeNBT(registries);
        return tag.contains("DifferentialDischargeAmount") ? tag.getDouble("DifferentialDischargeAmount")
            : FluidStack.parseOptional(registries, tag.getCompound("DifferentialDischarge")).getAmount();
    }

    @GameTest(template = TEMPLATE, batch = "pump_gravityStartsUnpoweredPump", timeoutTicks = 440)
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
        boolean[] supplySeen = {false};
        h.onEachTick(() -> { if(h.getTick()>10 && NetworkLoad.of(pump).status()==LoadFactor.Status.AVAILABLE) supplySeen[0]=true; });
        h.runAfterDelay(10, () -> {
            supplied[0] = source.getTankInventory().fill(new FluidStack(Fluids.WATER, 6000), FluidAction.EXECUTE);
            h.assertTrue(supplied[0] > 0 && source.getTankInventory().getFluidAmount() == supplied[0], "gravity source owns its accepted inventory");
        });
        h.runAfterDelay(400, () -> {
            h.assertTrue(destination.getTankInventory().getFluidAmount() > 200, "height difference transports water without a motor");
            h.assertTrue(Math.abs(pump.getSpeed()) > .1, "gravity-fed water backdrives the real pump");
            h.assertTrue(supplySeen[0], "actual fluid backdrive is shown as a shaft supply while gravitational energy is being released");
            double total = source.getTankInventory().getFluidAmount() + destination.getTankInventory().getFluidAmount() + localOwned(h);
            h.assertTrue(Math.abs(total - supplied[0]) < 1e-6, "gravity transfer conserves original tank inventory and fractions: expected=" + supplied[0] + "; actual="
                + total + "; source=" + source.getTankInventory().getFluidAmount() + "; destination=" + destination.getTankInventory().getFluidAmount() + "; owned=" + localOwned(h));
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_sameTankPortsCannotDrivePump", timeoutTicks = 130)
    public static void sameTankPortsCannotDrivePump(GameTestHelper h) {
        forceChunks(h);
        BlockPos tankPos = new BlockPos(6, 3, 8), pumpPos = new BlockPos(7, 5, 8);
        for (int y = 0; y < 3; y++) h.setBlock(tankPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, new BlockPos(7, 3, 8), Direction.WEST, Direction.EAST);
        pipe(h, new BlockPos(8, 3, 8), Direction.WEST, Direction.UP);
        pipe(h, new BlockPos(8, 4, 8), Direction.DOWN, Direction.UP);
        pipe(h, new BlockPos(8, 5, 8), Direction.DOWN, Direction.WEST);
        h.setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        FluidTankBlockEntity tank = h.getBlockEntity(tankPos); PumpBlockEntity pump = h.getBlockEntity(pumpPos);
        int[] total = {0};
        h.runAfterDelay(10, () -> {
            // A primed loop has no net height gain. An initially empty lower
            // branch may now legitimately fill using the tank's surface head.
            for (var pos : java.util.List.of(new BlockPos(7, 3, 8), new BlockPos(8, 3, 8),
                new BlockPos(8, 4, 8), new BlockPos(8, 5, 8), pumpPos)) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = new FluidStack(Fluids.WATER, 1);
                if (pipe.blockEntity instanceof PumpBlockEntity p) {
                    saved.pumpFacing = p.getBlockState().getValue(PumpBlock.FACING);
                    saved.core = com.diliupengyun.differential_create.kinetics.PipeContents.FULL;
                }
                for (var side : pipe.interfaces.keySet()) if (pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side))
                    saved.contents.put(side, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
            }
            total[0] = tank.getTankInventory().getCapacity();
            tank.getTankInventory().fill(new FluidStack(Fluids.WATER, total[0] - (int) localOwned(h)), FluidAction.EXECUTE);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(80, () -> {
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount() + localOwned(h) - total[0]) < 1e-8,
                "a primed same-tank loop conserves its original inventory");
            h.assertTrue(Math.abs(pump.getSpeed()) < 1e-7, "same-tank height difference supplies no free shaft energy");
            h.assertTrue(Math.abs(FluidKinetics.state(pump.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow) < 1e-7,
                "all same-tank ports use the controller's common pressure reference");
            h.succeed();
        });
    }

    private record LevelFixture(FluidTankBlockEntity tank, java.util.List<BlockPos> pipes, BlockPos corner) {}
    private static LevelFixture passiveTankLoop(GameTestHelper h) {
        forceChunks(h);
        BlockPos tankPos = new BlockPos(6, 1, 8), corner = tankPos.east(2);
        for (int y = 0; y < 4; y++) h.setBlock(tankPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        var pipes = new java.util.ArrayList<BlockPos>();
        for (int y : new int[]{0, 3}) {
            h.setBlock(tankPos.east().above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            pipes.add(tankPos.east().above(y));
            pipe(h, corner.above(y), Direction.WEST, y == 0 ? Direction.UP : Direction.DOWN);
            pipes.add(corner.above(y));
        }
        for (int y = 1; y < 3; y++) {
            h.setBlock(corner.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            pipes.add(corner.above(y));
        }
        return new LevelFixture(h.getBlockEntity(tankPos), pipes, corner);
    }
    private static double highestWater(GameTestHelper h, java.util.List<BlockPos> positions) {
        double highest = Double.NEGATIVE_INFINITY;
        for (var pos : positions) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
            var saved = FluidKinetics.state(pipe);
            for (var entry : saved.contents.entrySet()) for (var end : entry.getValue().data())
                highest = Math.max(highest, pipe.getPos().getY() + entry.getKey().getStepY() * .5 * end);
        }
        return highest;
    }

    @GameTest(template = TEMPLATE, batch = "pump_unpoweredTankFillsConnectedLowerPipe", timeoutTicks = 2010)
    public static void unpoweredTankFillsConnectedLowerPipe(GameTestHelper h) {
        var fixture = passiveTankLoop(h);
        double[] total = {0};
        h.runAfterDelay(10, () -> {
            var stock = fixture.tank.getTankInventory();
            // A higher initial surface also exercises inertia reaching the dry
            // upper return port; that port may receive but cannot supply water.
            total[0] = stock.fill(new FluidStack(Fluids.WATER, stock.getCapacity() * 5 / 8), FluidAction.EXECUTE);
        });
        h.onEachTick(() -> {
            if (total[0] == 0) return;
            double owned = fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(Math.abs(fixture.tank.getTankInventory().getFluidAmount() + owned - total[0]) < 1e-6,
                "passive priming transfers real tank inventory into pipes");
        });
        h.runAfterDelay(100, () -> {
            h.assertTrue(pipeOwned(h, fixture.corner.west()) >= 50 - 1e-7 && pipeOwned(h, fixture.corner) >= 50 - 1e-7,
                "the bottom macro water column connects without any pump or motor");
            h.assertTrue(pipeOwned(h, fixture.corner.above()) > 0, "tank surface pressure lifts water into the riser");
        });
        h.runAfterDelay(2000, () -> {
            var stock = fixture.tank.getTankInventory();
            double surface = fixture.tank.getBlockPos().getY() - 3.0 / 16
                + (fixture.tank.getHeight() - 9.0 / 16) * stock.getFluidAmount() / stock.getCapacity();
            double front = highestWater(h, fixture.pipes);
            // The ideal column retains a small oscillation at this finite time;
            // the independent equal-level fixture verifies exact equilibrium.
            h.assertTrue(Math.abs(front - surface) < .25,
                "the unpowered water front approaches the original tank's displayed surface: " + front + " / " + surface);
            h.assertTrue(pipeOwned(h, fixture.corner.above(3).west()) == 0,
                "the upper horizontal branch does not acquire fictitious water");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_matchingTankAndPipeLevelsRemainStill", timeoutTicks = 160)
    public static void matchingTankAndPipeLevelsRemainStill(GameTestHelper h) {
        var fixture = passiveTankLoop(h);
        double[] total = {0}, initialFront = {0};
        h.runAfterDelay(10, () -> {
            var stock = fixture.tank.getTankInventory();
            stock.fill(new FluidStack(Fluids.WATER, stock.getCapacity() * 3 / 8), FluidAction.EXECUTE);
            double aboveBottom = -3.0 / 16 + (fixture.tank.getHeight() - 9.0 / 16) * 3 / 8;
            for (var pos : fixture.pipes) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = new FluidStack(Fluids.WATER, 1);
                for (var side : pipe.interfaces.keySet()) if (pipe.canHaveFlowToward(pipe.blockEntity.getBlockState(), side)) {
                    double lower = pos.getY() - fixture.corner.getY(), upper = lower + side.getStepY() * .5;
                    double wet = side.getStepY() == 0 ? lower < aboveBottom ? 1 : 0
                        : Math.max(0, Math.min(1, (aboveBottom - Math.min(lower, upper)) / .5));
                    if (wet == 0) continue;
                    saved.contents.put(side, side == Direction.DOWN
                        ? com.diliupengyun.differential_create.kinetics.PipeContents.of(1 - wet, 1)
                        : com.diliupengyun.differential_create.kinetics.PipeContents.of(0, wet));
                }
            }
            total[0] = stock.getFluidAmount() + fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            initialFront[0] = highestWater(h, fixture.pipes);
            FluidKinetics.invalidate(h.getLevel());
        });
        h.runAfterDelay(140, () -> {
            for (var pos : fixture.pipes) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(),h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe);
                h.assertTrue(saved.phases != null && Math.abs(saved.phases.volume()-saved.queueCapacity)<1e-8,
                    "initial free-surface geometry creates its owned queue without inventing liquid");
            }
            double owned = fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(Math.abs(fixture.tank.getTankInventory().getFluidAmount() + owned - total[0]) < 1e-6,
                "hydrostatic equilibrium neither extracts nor creates fluid");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_changedTankLevelWakesDryPipe", timeoutTicks = 230)
    public static void changedTankLevelWakesDryPipe(GameTestHelper h) {
        var fixture = passiveTankLoop(h);
        int[] total = {0};
        h.runAfterDelay(10, () -> total[0] = fixture.tank.getTankInventory().fill(new FluidStack(Fluids.WATER, 10), FluidAction.EXECUTE));
        h.runAfterDelay(60, () -> {
            h.assertTrue(fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum() == 0,
                "a liquid surface below the side port cannot prime its dry pipe");
            total[0] += fixture.tank.getTankInventory().fill(new FluidStack(Fluids.WATER,
                fixture.tank.getTankInventory().getCapacity() * 5 / 8), FluidAction.EXECUTE);
        });
        h.runAfterDelay(210, () -> {
            double owned = fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(owned > 250, "raising the original tank level wakes the unchanged passive connection");
            h.assertTrue(Math.abs(fixture.tank.getTankInventory().getFluidAmount() + owned - total[0]) < 1e-6,
                "level changes retain tank and pipe ownership");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_unpoweredTankLevelDifferenceMovesWater", timeoutTicks = 220)
    public static void unpoweredTankLevelDifferenceMovesWater(GameTestHelper h) {
        forceChunks(h);
        BlockPos left = new BlockPos(5, 2, 8), right = new BlockPos(11, 2, 8);
        for (var pos : java.util.List.of(left, right)) for (int y = 0; y < 2; y++)
            h.setBlock(pos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        var pipes = new java.util.ArrayList<BlockPos>();
        for (int x = 1; x < 6; x++) { pipe(h, left.east(x), Direction.WEST, Direction.EAST); pipes.add(left.east(x)); }
        FluidTankBlockEntity source = h.getBlockEntity(left), receiver = h.getBlockEntity(right);
        int[] total = {0}, received = {0};
        h.runAfterDelay(10, () -> {
            int capacity = source.getTankInventory().getCapacity();
            total[0] = source.getTankInventory().fill(new FluidStack(Fluids.WATER, capacity * 3 / 4), FluidAction.EXECUTE);
            received[0] = receiver.getTankInventory().fill(new FluidStack(Fluids.WATER, capacity / 4), FluidAction.EXECUTE);
            total[0] += received[0];
        });
        h.runAfterDelay(200, () -> {
            h.assertTrue(receiver.getTankInventory().getFluidAmount() > received[0] + 500,
                "different water levels drive flow between tanks at the same block height without a pump");
            double owned = pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(Math.abs(source.getTankInventory().getFluidAmount() + receiver.getTankInventory().getFluidAmount() + owned - total[0]) < 1e-6,
                "passive tank-to-tank flow preserves original inventory and pipe fractions");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_dryTankPortAcceptsPipeLiquid", timeoutTicks = 260)
    public static void dryTankPortAcceptsPipeLiquid(GameTestHelper h) {
        pouringThroughDryTankPort(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "pump_tinyPrimingStepDoesNotFreezeDryPipe", timeoutTicks = 260)
    public static void tinyPrimingStepDoesNotFreezeDryPipe(GameTestHelper h) {
        pouringThroughDryTankPort(h, true);
    }

    private static void pouringThroughDryTankPort(GameTestHelper h, boolean tinyStep) {
        forceChunks(h);
        BlockPos targetPos = new BlockPos(6, 1, 8), sourcePos = new BlockPos(8, 3, 8), pipePos = sourcePos.west();
        for (int y = 0; y < 3; y++) h.setBlock(targetPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(sourcePos, AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, pipePos, Direction.WEST, Direction.EAST);
        FluidTankBlockEntity source = h.getBlockEntity(sourcePos), target = h.getBlockEntity(targetPos);
        int[] total = {0};
        h.runAfterDelay(10, () -> {
            total[0] = source.getTankInventory().fill(new FluidStack(Fluids.WATER, 12000), FluidAction.EXECUTE)
                + target.getTankInventory().fill(new FluidStack(Fluids.WATER, 16000), FluidAction.EXECUTE);
            if (tinyStep) {
                // No end interval can represent this first water displacement.
                // A later normal slice must still respond to the source pressure.
                NetworkTopology.flush();
                var frame = FluidKinetics.capture(h.getLevel());
                FluidKinetics.advance(frame, 1e-25);
            }
        });
        h.onEachTick(() -> {
            if (total[0] == 0) return;
            h.assertTrue(Math.abs(source.getTankInventory().getFluidAmount() + target.getTankInventory().getFluidAmount()
                + pipeOwned(h, pipePos) - total[0]) < 1e-6, "pouring through a dry upper port retains all real inventory");
        });
        h.runAfterDelay(240, () -> {
            h.assertTrue(target.getTankInventory().getFluidAmount() > 16100,
                "a dry upper tank port still accepts pipe liquid: source=" + source.getTankInventory().getFluidAmount()
                    + "; target=" + target.getTankInventory().getFluidAmount() + "/" + target.getTankInventory().getCapacity()
                    + "; height=" + target.getHeight() + "; pipe=" + pipeOwned(h, pipePos));
            double surface = target.getBlockPos().getY() - 3.0 / 16
                + (target.getHeight() - 9.0 / 16) * target.getTankInventory().getFluidAmount() / target.getTankInventory().getCapacity();
            h.assertTrue(surface < h.absolutePos(pipePos).getY(), "the receiving upper port remains above the actual tank surface");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_direction_macroJunctionMixingRetainsLiquid", timeoutTicks = 80)
    public static void macroJunctionMixingRetainsLiquid(GameTestHelper h) {
        coastingJunction(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "pump_direction_macroUpperTankBranchRetainsLiquid", timeoutTicks = 80)
    public static void macroUpperTankBranchRetainsLiquid(GameTestHelper h) {
        coastingJunction(h, true);
    }

    private static void coastingJunction(GameTestHelper h, boolean upperTank) {
        forceChunks(h);
        BlockPos centre = new BlockPos(8, 3, 8);
        for (int x = -4; x <= 4; x++) if (x != 0) pipe(h, centre.east(x), Direction.WEST, Direction.EAST);
        // Both open ends may discharge actual water; include those receipts in
        // conservation rather than assuming a moving packet can never escape.
        h.setBlock(centre.west(5), Blocks.AIR); h.setBlock(centre.east(5), Blocks.AIR);
        if (upperTank) {
            pipe(h, centre, Direction.WEST, Direction.EAST, Direction.UP);
            h.setBlock(centre.above(), AllBlocks.FLUID_TANK.getDefaultState());
        } else {
            pipe(h, centre, Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH);
            for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                for (int n = 1; n <= 4; n++) pipe(h, centre.relative(side, n), side, side.getOpposite());
                h.setBlock(centre.relative(side, 5), Blocks.STONE);
            }
        }
        double[] total = {-1};
        h.runAfterDelay(10, () -> {
            for (int x = -4; x <= 4; x++) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre.east(x)));
                var state = FluidKinetics.state(pipe); setInitialContents(state); state.fluid = new FluidStack(Fluids.WATER, 1);
                state.outward.put(Direction.WEST, -16d); state.outward.put(Direction.EAST, 16d);
            }
            var moving = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre.west(3)));
            FluidKinetics.state(moving).contents.put(Direction.WEST,
                com.diliupengyun.differential_create.kinetics.PipeContents.of(.2, .8));
            total[0] = localOwned(h); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (total[0] < 0) return;
            h.assertTrue(Math.abs(coastingInventory(h, centre) - total[0]) < 1e-6,
                "junction water is either retained or really discharged: " + coastingInventory(h, centre) + " / " + total[0]);
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre));
            var state = FluidKinetics.state(pipe);
            h.assertTrue(Double.isFinite(state.volume()) && state.outward.values().stream().allMatch(Double::isFinite),
                "mixed macro junction retains finite liquid and motion");
        });
        h.runAfterDelay(45, () -> {
            // The coarse model permits mixing and numerical momentum loss.
            // Movement and ownership remain required; an exact plug trajectory
            // through the junction is no longer part of its scope.
            h.assertTrue(pipeOwned(h, centre.west(3)) < total[0] - .1,
                "the initial liquid packet moves out of its original cell");
            h.assertTrue(Math.abs(coastingInventory(h, centre) - total[0]) < 1e-6,
                "mixing and damping do not discard water outside the actual discharge ledger");
            h.succeed();
        });
    }

    private static double coastingInventory(GameTestHelper h, BlockPos centre) {
        double total = localOwned(h);
        for (var side : java.util.List.of(Direction.WEST, Direction.EAST)) {
            var transport = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre.relative(side, 4)));
            var source = ((com.diliupengyun.differential_create.kinetics.IPipePressure) transport.getConnection(side)).differentialcreate$source();
            if (source instanceof com.simibubi.create.content.fluids.OpenEndedPipe open)
                total += discharged(h, open) + open.provideHandler().getCapability().getFluidInTank(0).getAmount();
        }
        if (h.getLevel().getBlockEntity(h.absolutePos(centre.above())) instanceof FluidTankBlockEntity tank)
            total += tank.getTankInventory().getFluidAmount();
        return total;
    }

    @GameTest(template = TEMPLATE, batch = "pump_direction_tankPressureOpensDryCrossBranches", timeoutTicks = 180)
    public static void tankPressureOpensDryCrossBranches(GameTestHelper h) {
        forceChunks(h);
        BlockPos centre = new BlockPos(8, 3, 8), sourcePos = centre.west(5);
        for (int y = 0; y < 4; y++) h.setBlock(sourcePos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        for (int x = -4; x <= 4; x++) if (x != 0) pipe(h, centre.east(x), Direction.WEST, Direction.EAST);
        h.setBlock(centre.east(5), Blocks.STONE);
        pipe(h, centre, Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            for (int n = 1; n <= 4; n++) pipe(h, centre.relative(side, n), side, side.getOpposite());
            h.setBlock(centre.relative(side, 5), Blocks.STONE);
        }
        FluidTankBlockEntity source = h.getBlockEntity(sourcePos);
        double[] total = {-1};
        double[] maximum = {0, 0};
        h.runAfterDelay(10, () -> {
            for (int x = -4; x <= 4; x++) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre.east(x)));
                var state = FluidKinetics.state(pipe); setInitialContents(state); state.fluid = new FluidStack(Fluids.WATER, 1);
                state.contents.put(Direction.WEST, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
                state.contents.put(Direction.EAST, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
            }
            source.getTankInventory().fill(new FluidStack(Fluids.WATER, 24000), FluidAction.EXECUTE);
            total[0] = source.getTankInventory().getFluidAmount() + localOwned(h); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (total[0] >= 0) h.assertTrue(Math.abs(source.getTankInventory().getFluidAmount() + localOwned(h) - total[0]) < 1e-6,
                "pressure-driven directional contact preserves tank and pipe water");
            var junction = FluidKinetics.state(com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre)));
            double north = junction.content(Direction.NORTH).fraction() * .5 * PipeFilling.MB_PER_METRE;
            double south = junction.content(Direction.SOUTH).fraction() * .5 * PipeFilling.MB_PER_METRE;
            for (int n = 1; n <= 4; n++) { north += pipeOwned(h, centre.north(n)); south += pipeOwned(h, centre.south(n)); }
            maximum[0] = Math.max(maximum[0], north); maximum[1] = Math.max(maximum[1], south);
            h.assertTrue(Math.abs(north - south) < 1e-5, "mirror branches respond equally while their sealed air stores and returns energy");
        });
        h.runAfterDelay(130, () -> {
            // Closed air can return water before this final snapshot. Count
            // actual invasion of each side, including the junction's half-arm.
            h.assertTrue(maximum[0] > 1 && maximum[1] > 1, "actual tank pressure sends liquid into both initially dry side branches");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_direction_crossingSlugsKeepFiniteMotionAndEnergy", timeoutTicks = 340)
    public static void crossingSlugsKeepFiniteMotionAndEnergy(GameTestHelper h) {
        forceChunks(h);
        BlockPos centre = new BlockPos(8, 3, 8);
        for (int n = -5; n <= 5; n++) if (n != 0) {
            pipe(h, centre.east(n), Direction.WEST, Direction.EAST);
            pipe(h, centre.south(n), Direction.NORTH, Direction.SOUTH);
        }
        pipe(h, centre, Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH);
        for (Direction side : new Direction[]{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH})
            h.setBlock(centre.relative(side, 6), Blocks.STONE);
        double[] total = {-1}, energy = {-1};
        h.runAfterDelay(10, () -> {
            for (int n = -5; n <= 5; n++) {
                for (var pos : new BlockPos[]{centre.east(n), centre.south(n)}) {
                    var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                    var state = FluidKinetics.state(pipe); setInitialContents(state); state.fluid = new FluidStack(Fluids.WATER, 1);
                    for (Direction side : pipe.interfaces.keySet()) state.outward.put(side,
                        side == Direction.WEST || side == Direction.NORTH ? -16d : 16d);
                }
            }
            for (Direction side : new Direction[]{Direction.WEST, Direction.NORTH}) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(centre.relative(side, 3)));
                FluidKinetics.state(pipe).contents.put(side, com.diliupengyun.differential_create.kinetics.PipeContents.of(.2, .8));
            }
            total[0] = localOwned(h); energy[0] = localLiquidEnergy(h); FluidKinetics.invalidate(h.getLevel());
        });
        h.onEachTick(() -> {
            if (total[0] < 0) return;
            h.assertTrue(Math.abs(localOwned(h) - total[0]) < 1e-6, "crossing liquid fronts keep their actual inventory");
            double kinetic = localLiquidEnergy(h);
            h.assertTrue(Double.isFinite(kinetic) && kinetic <= energy[0] + 1e-8,
                "unpowered horizontal contact cannot create liquid energy");
        });
        h.runAfterDelay(310, h::succeed);
    }

    private static double localLiquidEnergy(GameTestHelper h) {
        double total = 0, inertia = new IdealFluidSolver.Material(1000, false).inertia(.5);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 10; y++) for (int z = 0; z < 16; z++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(new BlockPos(x, y, z)));
            if (pipe == null) continue;
            var state = FluidKinetics.state(pipe);
            for (var entry : state.contents.entrySet()) {
                var motion = state.motion(entry.getKey());
                for (int i = 0; i < motion.contents().intervals(); i++) {
                    double q = motion.flow()[i];
                    total += .5 * inertia * (motion.contents().end(i) - motion.contents().start(i)) * q * q;
                }
            }
        }
        return total;
    }

    private static double localOwned(GameTestHelper h) {
        double amount = 0;
        for (int x = 0; x < 16; x++) for (int y = 0; y < 10; y++) for (int z = 0; z < 16; z++) {
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(new BlockPos(x, y, z)));
            if (pipe != null) {
                amount += FluidKinetics.state(pipe).volume();
                for (var buffer : FluidKinetics.state(pipe).buffers.values()) amount += buffer.amount;
            }
        }
        return amount;
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_dryTankPortWaitsForLiquidContact", timeoutTicks = 280)
    public static void dryTankPortWaitsForLiquidContact(GameTestHelper h) {
        var fixture = suctionLoop(h);
        var motor = fixture.motor; var tank = fixture.tank; var pipes = fixture.pipes; var pumpPos = fixture.pumpPos;
        double[] suppliedTotal = {16000};
        h.runAfterDelay(20, () -> {
            tank.getTankInventory().fill(new FluidStack(Fluids.WATER, 16000), FluidAction.EXECUTE);
            suctionSpeed(motor, 32);
        });
        h.runAfterDelay(70, () -> {
            PumpBlockEntity pump = h.getBlockEntity(pumpPos);
            h.assertTrue(Math.abs(Math.abs(pump.getSpeed()) - 64) < .1, "test uses 16-m suction at 64 RPM");
            // The immersed lower return can fill backwards by gravity. The
            // exposed upper port still supplies neither water nor pump suction.
            h.assertTrue(pipeOwned(h, pumpPos.above(26)) == 0,
                "a dry upper tank port cannot draw liquid below the surface");
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount()
                + pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum() - 16000) < 1e-6,
                "gravity filling through the wet lower port conserves actual inventory");
            var saved = pump.saveWithoutMetadata(h.getLevel().registryAccess());
            var restored = pump.getType().create(pump.getBlockPos(), pump.getBlockState());
            restored.loadWithComponents(saved, h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(pump.getBlockPos()); h.getLevel().setBlockEntity(restored);
        });
        h.runAfterDelay(100, () -> {
            h.assertTrue(pipeOwned(h, pumpPos.above(26)) == 0,
                "cold reload preserves the dry upper-port constraint");
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount()
                + pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum() - 16000) < 1e-6,
                "cold reload preserves gravity-filled lower water");
            suctionSpeed(motor, 64);
        });
        h.runAfterDelay(180, () -> {
            double owned = pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(pipeOwned(h, pumpPos.above(26)) == 0,
                "a stronger pump cannot draw liquid through a port above the surface");
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount() + owned - 16000) < 1e-6,
                "changing the wet lower pump speed cannot create water");
            suppliedTotal[0] += tank.getTankInventory().fill(new FluidStack(Fluids.WATER, tank.getTankInventory().getCapacity()), FluidAction.EXECUTE);
        });
        h.runAfterDelay(250, () -> {
            double owned = pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            // Coarse sealed air can stop further priming; verify admission at
            // the newly immersed mouth rather than an exact parcel trajectory.
            h.assertTrue(pipeOwned(h, pumpPos.above(26)) > 1e-6,
                "raising the tank surface above the upper port admits real liquid there");
            h.assertTrue(Math.abs(tank.getTankInventory().getFluidAmount() + owned - suppliedTotal[0]) < 1e-6,
                "a shared upper/lower tank conserves inventory while priming the loop");
            h.succeed();
        });
    }

    private record SuctionFixture(CreativeMotorBlockEntity motor, FluidTankBlockEntity tank,
                                   java.util.List<BlockPos> pipes, BlockPos pumpPos, BlockPos corner) {}
    private static SuctionFixture suctionLoop(GameTestHelper h) {
        BlockPos pumpPos = new BlockPos(7, 1, 8), tankPos = pumpPos.east(), corner = pumpPos.west();
        var pipes = new java.util.ArrayList<BlockPos>();
        for (int y = 0; y <= 26; y++) h.setBlock(tankPos.above(y), AllBlocks.FLUID_TANK.getDefaultState());
        pipe(h, corner, Direction.EAST, Direction.UP); pipes.add(corner);
        for (int y = 1; y < 26; y++) {
            h.setBlock(corner.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            pipes.add(corner.above(y));
        }
        pipe(h, corner.above(26), Direction.DOWN, Direction.EAST); pipes.add(corner.above(26));
        pipe(h, pumpPos.above(26), Direction.WEST, Direction.EAST); pipes.add(pumpPos.above(26));
        h.setBlock(pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST)); pipes.add(pumpPos);
        h.setBlock(pumpPos.south(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(pumpPos.south().west(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(pumpPos.south().west(2), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = h.getBlockEntity(pumpPos.south().west(2)); motor.generatedSpeed.setValue(0);
        FluidTankBlockEntity tank = h.getBlockEntity(tankPos);
        return new SuctionFixture(motor, tank, pipes, pumpPos, corner);
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_breaking_breakingSuctionTeeRetainsValidFronts", timeoutTicks = 180)
    public static void breakingSuctionTeeRetainsValidFronts(GameTestHelper h) {
        var fixture = suctionLoop(h);
        BlockPos junction = fixture.corner.above(4), branch = junction.east();
        pipe(h, junction, Direction.DOWN, Direction.UP, Direction.EAST);
        pipe(h, branch, Direction.WEST, Direction.EAST);
        double[] total = {0};
        h.runAfterDelay(20, () -> {
            var fluid = new FluidStack(Fluids.WATER, 1);
            fixture.tank.getTankInventory().fill(fluid.copyWithAmount(16000), FluidAction.EXECUTE);
            suctionSpeed(fixture.motor, 32);
            double q = 1.1345337505861621e-5;
            // Preserve the crashed column's actual partial ownership and its tiny
            // upper-mouth remainder while exercising the real block-removal path.
            for (var pos : fixture.pipes) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = fluid.copy();
                for (var side : pipe.interfaces.keySet()) {
                    saved.contents.put(side, pos.getY() <= junction.getY()
                        ? com.diliupengyun.differential_create.kinetics.PipeContents.FULL
                        : com.diliupengyun.differential_create.kinetics.PipeContents.EMPTY);
                    saved.outward.put(side, side == Direction.UP || side == Direction.EAST && pos.getY() > junction.getY() ? q : -q);
                }
                if (pos.equals(junction.above())) saved.contents.put(Direction.DOWN,
                    com.diliupengyun.differential_create.kinetics.PipeContents.of(.9183650709088671, .957612298185955, .9576122981862214, 1));
                if (pos.equals(fixture.pumpPos.above(26))) saved.contents.put(Direction.EAST,
                    com.diliupengyun.differential_create.kinetics.PipeContents.of(.9999999999968854, 1));
                if (pos.equals(fixture.pumpPos)) {
                    saved.core = com.diliupengyun.differential_create.kinetics.PipeContents.FULL;
                    saved.pumpFlow = -q; saved.pumpFacing = Direction.EAST;
                    saved.outward.put(Direction.WEST, q); saved.outward.put(Direction.EAST, -q);
                }
            }
            var branchPipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(branch));
            var saved = FluidKinetics.state(branchPipe); setInitialContents(saved); saved.fluid = fluid.copy();
            saved.contents.put(Direction.WEST, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
            saved.contents.put(Direction.EAST, com.diliupengyun.differential_create.kinetics.PipeContents.FULL);
            h.getLevel().destroyBlock(h.absolutePos(branch), false);
            total[0] = fixture.tank.getTankInventory().getFluidAmount()
                + fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
        });
        h.onEachTick(() -> {
            if (total[0] == 0) return;
            for (var pos : fixture.pipes) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var packet = new net.minecraft.nbt.CompoundTag();
                FluidKinetics.state(pipe).write(packet, h.getLevel().registryAccess());
                var decoded = new com.diliupengyun.differential_create.kinetics.PipeHydraulics();
                decoded.read(packet, h.getLevel().registryAccess());
                h.assertTrue(decoded.volume() == FluidKinetics.state(pipe).volume(), "every surviving pipe retains readable client/NBT intervals");
            }
        });
        h.runAfterDelay(150, () -> {
            PumpBlockEntity pump = h.getBlockEntity(fixture.pumpPos);
            h.assertTrue(Double.isFinite(pump.getSpeed()) && Double.isFinite(((IPumpDynamics) pump).differentialcreate$flow()),
                "breaking a running three-way connection cannot stall the physical clock or corrupt pump flow");
            double after = fixture.tank.getTankInventory().getFluidAmount()
                + fixture.pipes.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
            h.assertTrue(Math.abs(after - total[0]) < 1e-6, "surviving column and tank conserve water after branch removal: " + after + " / " + total[0]);
            h.succeed();
        });
    }

    private static void suctionSpeed(CreativeMotorBlockEntity motor, int setting) {
        // One real cog reverses this east-facing motor; positive suction
        // settings must produce positive X rotation at the return pump.
        motor.generatedSpeed.setValue(-setting); NetworkTopology.flush();
        var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
        double speed = SourcePhysics.curve(motor).noLoadSpeed(); float reference = ratios.get(motor);
        ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = speed * ratio / reference);
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_placing_suction_placingSuctionBranchAdvancesWithValidInventory", timeoutTicks = 240)
    public static void placingSuctionBranchAdvancesWithValidInventory(GameTestHelper h) {
        placingBranch(h, false);
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_placing_pressure_placingPressureBranchAdvancesWithValidInventory", timeoutTicks = 240)
    public static void placingPressureBranchAdvancesWithValidInventory(GameTestHelper h) {
        placingBranch(h, true);
    }

    private static void placingBranch(GameTestHelper h, boolean pushing) {
        var fixture = suctionLoop(h);
        if (pushing) h.setBlock(fixture.pumpPos, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.WEST));
        BlockPos junction = fixture.corner.above(15), branch = junction.east();
        pipe(h, junction, Direction.DOWN, Direction.UP);
        var watched = new java.util.ArrayList<>(fixture.pipes); watched.add(branch);
        double[] total = {0};
        h.runAfterDelay(20, () -> {
            var fluid = new FluidStack(Fluids.WATER, 1);
            fixture.tank.getTankInventory().fill(fluid.copyWithAmount(16000), FluidAction.EXECUTE);
            suctionSpeed(fixture.motor, 32);
            pipe(h, branch, Direction.WEST, Direction.EAST);
            pipe(h, junction, Direction.DOWN, Direction.UP, Direction.EAST);
            // The placement crash retained a slow lower column and opposite flows
            // between two almost empty upper mouths. Keep that detached state to
            // exercise event predictions during the actual topology change.
            double lower = 7.29150564383068e-7, upper = 62.35266648986439;
            for (var pos : fixture.pipes) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = fluid.copy();
                for (var side : pipe.interfaces.keySet()) {
                    saved.contents.put(side, pos.getY() <= fixture.corner.getY() + 4
                        ? com.diliupengyun.differential_create.kinetics.PipeContents.FULL
                        : com.diliupengyun.differential_create.kinetics.PipeContents.EMPTY);
                    double q = pos.getY() > junction.getY() ? upper : -lower;
                    saved.outward.put(side, side == Direction.UP || side == Direction.EAST ? q : -q);
                }
                if (pos.equals(fixture.corner.above(5))) saved.contents.put(Direction.DOWN,
                    com.diliupengyun.differential_create.kinetics.PipeContents.of(.9183650717023051, .9576122989793929, .9576122989796594, 1));
                if (pos.equals(fixture.pumpPos.above(26))) saved.contents.put(Direction.EAST,
                    com.diliupengyun.differential_create.kinetics.PipeContents.of(.9999999999998117, 1));
                if (pos.equals(junction)) {
                    saved.outward.put(Direction.UP, upper);
                    saved.outward.put(Direction.EAST, -(upper + lower));
                }
                if (pos.equals(fixture.pumpPos)) {
                    saved.core = com.diliupengyun.differential_create.kinetics.PipeContents.FULL;
                    saved.pumpFlow = pushing ? -lower : lower; saved.pumpFacing = pushing ? Direction.WEST : Direction.EAST;
                    saved.outward.put(Direction.WEST, -lower); saved.outward.put(Direction.EAST, lower);
                }
            }
            var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(branch));
            var saved = FluidKinetics.state(pipe); setInitialContents(saved); saved.fluid = fluid.copy();
            saved.contents.put(Direction.EAST, com.diliupengyun.differential_create.kinetics.PipeContents.of(.9999999991992271, 1));
            saved.outward.put(Direction.WEST, upper + lower); saved.outward.put(Direction.EAST, -(upper + lower));
            total[0] = fixture.tank.getTankInventory().getFluidAmount() + watched.stream().mapToDouble(pos -> pipeOwned(h, pos)).sum();
        });
        h.onEachTick(() -> {
            if (total[0] == 0) return;
            double actual = fixture.tank.getTankInventory().getFluidAmount();
            for (var pos : watched) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(pos));
                if (pipe == null) continue;
                var packet = new net.minecraft.nbt.CompoundTag(); var state = FluidKinetics.state(pipe);
                state.write(packet, h.getLevel().registryAccess());
                var decoded = new com.diliupengyun.differential_create.kinetics.PipeHydraulics();
                decoded.read(packet, h.getLevel().registryAccess());
                h.assertTrue(decoded.volume() == state.volume(), "placed/reconnected pipes publish readable owned intervals");
                h.assertTrue(state.outward.values().stream().allMatch(Double::isFinite), "placed branch has finite physical flow");
                actual += pipeOwned(h, pos);
            }
            h.assertTrue(Math.abs(actual - total[0]) < 1e-6, "placement and reconnection conserve retained water: " + actual + " / " + total[0]);
        });
        for (int tick : new int[]{70, 130}) h.runAfterDelay(tick, () -> {
            total[0] -= pipeOwned(h, branch);
            h.getLevel().destroyBlock(h.absolutePos(branch), false);
        });
        for (int tick : new int[]{95, 155}) h.runAfterDelay(tick, () -> pipe(h, branch, Direction.WEST, Direction.EAST));
        h.runAfterDelay(210, () -> {
            PumpBlockEntity pump = h.getBlockEntity(fixture.pumpPos);
            h.assertTrue(Double.isFinite(pump.getSpeed()) && Double.isFinite(((IPumpDynamics) pump).differentialcreate$flow()),
                "clock advances through placement, removal and repeated reconnection of the suction branch");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_insufficientHeadFillsLowerPipes", timeoutTicks = 460)
    public static void insufficientHeadFillsLowerPipes(GameTestHelper h) {
        Fixture f = fixture(h, false, false);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(BOILER.offset(x, y, z), Blocks.AIR);
        BlockPos corner = PUMP.east(4), target = corner.above(38);
        pipe(h, corner, Direction.WEST, Direction.UP);
        h.setBlock(PUMP.east(2), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        for (int y = 1; y < 38; y++) h.setBlock(corner.above(y), AllBlocks.GLASS_FLUID_PIPE.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        h.setBlock(target, AllBlocks.FLUID_TANK.getDefaultState());
        FluidTankBlockEntity destination = h.getBlockEntity(target);
        // Avoid racing the fixture's separate startup callback on tick ten.
        h.runAfterDelay(11, () -> { f.motor.generatedSpeed.setValue(-32); f.speed(SourcePhysics.curve(f.motor).noLoadSpeed()); });
        h.runAfterDelay(400, () -> {
            h.assertTrue(Math.abs(Math.abs(f.pump.getSpeed()) - 64) < .1,
                "fixture uses the reported 64-RPM pump speed: actual=" + f.pump.getSpeed() + "; head=" + ((IPumpDynamics) f.pump).differentialcreate$head()
                    + "; motor=" + f.motor.generatedSpeed.getValue() + "; target=" + SourcePhysics.curve(f.motor).noLoadSpeed());
            h.assertTrue(destination.getTankInventory().getFluidAmount() == 0, "16-m head cannot deliver water to the 38-m receiver");
            var horizontal = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east(2)));
            h.assertTrue(FluidKinetics.state(horizontal).volume() >= 50 - 1e-7
                && FluidKinetics.state(horizontal).volume() <= 100 + 1e-7,
                "horizontal glass pipe retains the connected macro water column despite insufficient final lift");
            double total = f.water() + destination.getTankInventory().getFluidAmount();
            net.minecraft.world.level.block.entity.BlockEntity partial = null;
            for (int x = -4; x <= 4; x++) total += pipeOwned(h, PUMP.east(x));
            for (int y = 1; y < 38; y++) {
                total += pipeOwned(h, corner.above(y));
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(corner.above(y)));
                double volume = FluidKinetics.state(pipe).volume();
                if (volume > 1e-6) partial = pipe.blockEntity;
            }
            h.assertTrue(Math.abs(total - 16000) < 1e-6, "filling conserves tank, pipe and fractional inventory: " + total);
            h.assertTrue(pipeOwned(h, corner.above()) > 99, "water enters the rising pipe before stopping at its pressure-supported height");
            h.assertTrue(pipeOwned(h, corner.above(36)) < 1e-8, "upper transparent pipe remains genuinely empty");
            h.assertTrue(partial != null, "the supported lower column remains owned below the genuinely empty upper pipes");
            var saved = partial.saveWithoutMetadata(h.getLevel().registryAccess());
            var restored = partial.getType().create(partial.getBlockPos(), partial.getBlockState());
            restored.loadWithComponents(saved, h.getLevel().registryAccess());
            var oldPipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), partial.getBlockPos());
            var newPipe = ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity) restored).getBehaviour(FluidTransportBehaviour.TYPE);
            h.assertTrue(Math.abs(FluidKinetics.state(oldPipe).volume() - FluidKinetics.state(newPipe).volume()) < 1e-10,
                "cold pipe NBT preserves its actual partial water column");
            h.getLevel().removeBlockEntity(partial.getBlockPos()); h.getLevel().setBlockEntity(restored);
            ((com.diliupengyun.differential_create.kinetics.IPipePressure) horizontal.getConnection(Direction.EAST))
                .differentialcreate$showFlow(new FluidStack(Fluids.WATER, 1), 0);
            h.assertTrue(horizontal.getFlow(Direction.EAST) != null, "stationary owned water retains its transparent-pipe presentation");
            h.runAfterDelay(20, () -> {
                h.assertTrue(destination.getTankInventory().getFluidAmount() == 0, "reload does not move the front to the high receiver");
                double after = f.water();
                for (int x = -4; x <= 4; x++) after += pipeOwned(h, PUMP.east(x));
                for (int y = 1; y < 38; y++) after += pipeOwned(h, corner.above(y));
                h.assertTrue(Math.abs(after - 16000) < 1e-6, "reloaded partial pipe neither duplicates nor loses real water: " + after);
                h.succeed();
            });
        });
    }
    private static double pipeOwned(GameTestHelper h, BlockPos position) {
        var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(position));
        if (pipe == null) return 0;
        var saved = FluidKinetics.state(pipe); double volume = saved.volume();
        for (var buffer : saved.buffers.values()) volume += buffer.amount;
        return volume;
    }
    @GameTest(template = TEMPLATE, batch = "pump_filling_twoPumpsShareOneMillibucket", timeoutTicks = 800)
    public static void twoPumpsShareOneMillibucket(GameTestHelper h) {
        // Bottom ports remain immersed even when only one mB is left. Side
        // ports cannot extract that stock under the actual-contact rule.
        BlockPos sourcePos = new BlockPos(8, 3, 8), left = new BlockPos(5, 2, 8), right = new BlockPos(12, 2, 8);
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
            h.setBlock(sourcePos.offset(x, 0, z), AllBlocks.FLUID_TANK.getDefaultState());
        BlockPos targetLeftPos = left.west(2), targetRightPos = right.east(2);
        h.setBlock(targetLeftPos, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(targetRightPos, AllBlocks.FLUID_TANK.getDefaultState());
        var leftPipes = java.util.List.of(sourcePos.below(), left.east(2), left.east(), left, left.west());
        var rightPipes = java.util.List.of(sourcePos.east().below(), right.west(2), right.west(), right, right.east());
        pipe(h, sourcePos.below(), Direction.UP, Direction.WEST);
        pipe(h, sourcePos.east().below(), Direction.UP, Direction.EAST);
        for (var pos : java.util.List.of(left.east(2), left.east(), left.west(), right.west(2), right.west(), right.east()))
            pipe(h, pos, Direction.WEST, Direction.EAST);
        h.setBlock(left, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.WEST));
        h.setBlock(right, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        var motors = new java.util.ArrayList<CreativeMotorBlockEntity>();
        for (var pump : java.util.List.of(left, right)) {
            h.setBlock(pump.above(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            BlockPos motorPos = pump.equals(left) ? pump.above().west() : pump.above().east();
            h.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,
                pump.equals(left) ? Direction.EAST : Direction.WEST));
            CreativeMotorBlockEntity motor = h.getBlockEntity(motorPos); motor.generatedSpeed.setValue(0); motors.add(motor);
        }
        FluidTankBlockEntity source = h.getBlockEntity(sourcePos), targetLeft = h.getBlockEntity(targetLeftPos), targetRight = h.getBlockEntity(targetRightPos);
        boolean[] extractionChecked = {false};
        h.runAfterDelay(20, () -> {
            source.getTankInventory().fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE);
            // Both actual pump shafts rotate positively, so their opposite
            // arrows draw away from the shared source. Each cog reverses its
            // motor; the two motors face opposite directions.
            for (var motor : motors) motor.generatedSpeed.setValue(
                motor.getBlockState().getValue(BlockStateProperties.FACING) == Direction.EAST ? -32 : 32);
            NetworkTopology.flush();
            for (var motor : motors) {
                var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
                double speed = SourcePhysics.curve(motor).noLoadSpeed(); float reference = ratios.get(motor);
                ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = speed * ratio / reference);
            }
        });
        h.onEachTick(() -> {
            if (h.getTick() <= 20) return;
            double leftOwned = targetLeft.getTankInventory().getFluidAmount(), rightOwned = targetRight.getTankInventory().getFluidAmount();
            for (var pos : leftPipes) leftOwned += pipeOwned(h, pos);
            for (var pos : rightPipes) rightOwned += pipeOwned(h, pos);
            h.assertTrue(Math.abs(leftOwned + rightOwned + source.getTankInventory().getFluidAmount() - 1) < 1e-8,
                "continued empty-tank air exchange preserves the actual shared millibucket");
            if (extractionChecked[0] || source.getTankInventory().getFluidAmount() != 0) return;
            h.assertTrue(leftOwned >= 0 && rightOwned >= 0 && Math.abs(leftOwned + rightOwned - 1) < 1e-6,
                "independent networks arbitrate one native integer withdrawal without duplicating it: " + leftOwned + " / " + rightOwned);
            extractionChecked[0] = true;
        });
        h.runAfterDelay(700, () -> {
            double leftOwned = targetLeft.getTankInventory().getFluidAmount(), rightOwned = targetRight.getTankInventory().getFluidAmount();
            for (var pos : leftPipes) leftOwned += pipeOwned(h, pos);
            for (var pos : rightPipes) rightOwned += pipeOwned(h, pos);
            h.assertTrue(source.getTankInventory().getFluidAmount() == 0, "both ports draw from one actual finite source");
            h.assertTrue(Math.abs(leftOwned + rightOwned - 1) < 1e-8,
                "two source ports cannot double one mB: " + leftOwned + " / " + rightOwned);
            h.assertTrue(extractionChecked[0], "initial shared extraction was observed before subsequent air/liquid motion");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = "pump_filling_sharedReceiverCombinesPendingReceipts", timeoutTicks = 120)
    public static void sharedReceiverCombinesPendingReceipts(GameTestHelper h) {
        BlockPos targetPos = new BlockPos(8, 3, 8), left = targetPos.west(3), right = targetPos.east(3);
        h.setBlock(targetPos, AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(targetPos.west(5), AllBlocks.FLUID_TANK.getDefaultState());
        h.setBlock(targetPos.east(5), AllBlocks.FLUID_TANK.getDefaultState());
        for (int x = -4; x <= 4; x++) if (x != 0 && Math.abs(x) != 3)
            pipe(h, targetPos.east(x), Direction.WEST, Direction.EAST);
        h.setBlock(left, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.EAST));
        h.setBlock(right, AllBlocks.MECHANICAL_PUMP.getDefaultState().setValue(PumpBlock.FACING, Direction.WEST));
        for (int x = -4; x <= 3; x++) h.setBlock(targetPos.above().east(x),
            (Math.abs(x) == 3 ? AllBlocks.COGWHEEL : AllBlocks.SHAFT).getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        h.setBlock(targetPos.above().west(5), AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        CreativeMotorBlockEntity motor = h.getBlockEntity(targetPos.above().west(5)); motor.generatedSpeed.setValue(0);
        FluidTankBlockEntity sourceLeft = h.getBlockEntity(targetPos.west(5)), sourceRight = h.getBlockEntity(targetPos.east(5)), target = h.getBlockEntity(targetPos);
        h.runAfterDelay(20, () -> {
            sourceLeft.getTankInventory().fill(new FluidStack(Fluids.WATER, 12000), FluidAction.EXECUTE);
            sourceRight.getTankInventory().fill(new FluidStack(Fluids.WATER, 12000), FluidAction.EXECUTE);
            // One cog reverses the east-facing motor. Positive pump rotation
            // sends both opposing arrows into the shared receiving tank.
            motor.generatedSpeed.setValue(-32); NetworkTopology.flush();
            var ratios = ((INetworkDynamics) motor.getOrCreateNetwork()).differentialcreate$getRatios();
            double speed = SourcePhysics.curve(motor).noLoadSpeed(); float reference = ratios.get(motor);
            ratios.forEach((be, ratio) -> ElasticKinetics.state(be).physicalSpeed = speed * ratio / reference);
        });
        double[] total = {0};
        h.runAfterDelay(80, () -> {
            target.getTankInventory().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            target.getTankInventory().fill(new FluidStack(Fluids.WATER, target.getTankInventory().getCapacity() - 1), FluidAction.EXECUTE);
            for (int sign : new int[]{-1, 1}) {
                var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(targetPos.east(sign)));
                var buffer = FluidKinetics.state(pipe).buffers.computeIfAbsent(sign < 0 ? Direction.EAST : Direction.WEST,
                    side -> new com.diliupengyun.differential_create.kinetics.PipeHydraulics.Buffer());
                buffer.amount = sign < 0 ? .4 : .5999999999999992;
                buffer.fluid = new FluidStack(Fluids.WATER, 1); buffer.delivery = true;
            }
            total[0] = sourceLeft.getTankInventory().getFluidAmount() + sourceRight.getTankInventory().getFluidAmount()
                + target.getTankInventory().getFluidAmount() + localOwned(h);
        });
        h.runAfterDelay(105, () -> {
            h.assertTrue(target.getTankInventory().getFluidAmount() == target.getTankInventory().getCapacity(),
                "shared receipt completes exactly one integer across both ports");
            double after = sourceLeft.getTankInventory().getFluidAmount() + sourceRight.getTankInventory().getFluidAmount()
                + target.getTankInventory().getFluidAmount() + localOwned(h);
            h.assertTrue(Math.abs(after - total[0]) < 1e-6, "shared receiver neither duplicates the last mB nor deletes a port's fraction");
            PumpBlockEntity pumpLeft = h.getBlockEntity(left), pumpRight = h.getBlockEntity(right);
            h.assertTrue(FluidKinetics.state(pumpLeft.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow == 0
                && FluidKinetics.state(pumpRight.getBehaviour(FluidTransportBehaviour.TYPE)).pumpFlow == 0,
                "both wet mouths observe the shared full-receiver constraint");
            h.succeed();
        });
    }
}

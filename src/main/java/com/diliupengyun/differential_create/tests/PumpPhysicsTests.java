package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
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
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
        if (boiler) h.setBlock(BOILER.south(2), AllBlocks.STEAM_ENGINE.getDefaultState()
            .setValue(SteamEngineBlock.FACE, AttachFace.WALL).setValue(SteamEngineBlock.FACING, Direction.SOUTH));
        var pipe = AllBlocks.FLUID_PIPE.getDefaultState().setValue(FluidPipeBlock.WEST, true).setValue(FluidPipeBlock.EAST, true);
        for (int x = -4; x <= 4; x++) if (x != 0) h.setBlock(PUMP.east(x), pipe);
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
            f.inlet.getTankInventory().fill(new FluidStack(Fluids.WATER, 8000), FluidAction.EXECUTE);
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
                double pressure = pipe.getConnection(Direction.WEST).getPressure().getFirst();
                h.assertTrue(Math.abs(pressure - Math.abs(f.pump.getSpeed())) < 0.1,
                    "a reloaded pipe follows the still-loaded coasting pump: " + pressure + " vs " + f.pump.getSpeed());
                h.assertTrue(before - f.water() > 1500, "water transfer resumes after pipe reload");
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
            int before = f.water(), output = f.boiler.getTankInventory().getFluidAmount();
            h.runAfterDelay(20, () -> {
                int transferred = before - f.water();
                h.assertTrue(transferred >= 600 && transferred <= 660, "flow rate follows 64 RPM without rebuilding the fluid network: " + transferred);
                h.assertTrue(f.boiler.getTankInventory().getFluidAmount() - output == transferred, "source and destination conserve water");
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
    public static void sharedPipeTracksEachPump(GameTestHelper h) {
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
            assertSharedPressure(h, 128, 64);
            setSecondSpeed(secondMotor, 32);
            h.runAfterDelay(10, () -> {
                assertSharedPressure(h, 128, 32);
                secondMotor.generatedSpeed.setValue(0);
                secondPump.getOrCreateNetwork().members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
                h.runAfterDelay(10, () -> {
                    assertSharedPressure(h, 128, 0);
                    h.assertTrue(Math.abs(f.pump.getSpeed()) > 127, "stopping the other pump cannot change this drive");
                    h.succeed();
                });
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

    private static void assertSharedPressure(GameTestHelper h, double first, double second) {
        var pipe = com.simibubi.create.content.fluids.FluidPropagator.getPipe(h.getLevel(), h.absolutePos(PUMP.east()));
        for (Direction side : new Direction[]{Direction.WEST, Direction.EAST}) {
            var pressure = pipe.getConnection(side).getPressure();
            double high = Math.max(pressure.getFirst(), pressure.getSecond());
            double low = Math.min(pressure.getFirst(), pressure.getSecond());
            h.assertTrue(Math.abs(high - first - second) < 0.1 && low == 0,
                "aligned pumps add their independent pressure: expected " + (first + second) + "/0, got " + high + "/" + low);
        }
    }

    private static void coasting(GameTestHelper h, boolean flywheel) {
        Fixture f = fixture(h, flywheel);
        h.runAfterDelay(80, () -> {
            h.assertTrue(f.boiler.boiler.waterSupply > 20, "powered pump supplies water to the real boiler");
            h.assertTrue(f.water() < 7000, "powered pump drains its input tank");
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
                h.assertTrue(before - f.water() > 1500, "coasting pump continues transporting water while its speed changes");
                h.runAfterDelay(70, () -> {
                    h.assertTrue(f.boiler.boiler.waterSupply > 10, "boiler water survives beyond the previous supply sampling window");
                    var network = f.pump.getOrCreateNetwork();
                    network.members.keySet().forEach(be -> ElasticKinetics.state(be).physicalSpeed = 0);
                    h.runAfterDelay(10, () -> {
                        int stopped = f.water();
                        h.runAfterDelay(20, () -> {
                            h.assertTrue(stopped == f.water(), "a stopped pump cannot keep transporting cached flow");
                            h.succeed();
                        });
                    });
                });
            });
        });
    }
}

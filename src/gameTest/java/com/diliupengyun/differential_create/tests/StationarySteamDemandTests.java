package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real world tanks, engines, shafts and native boiler fill capability. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class StationarySteamDemandTests {
    private static final BlockPos TANK = new BlockPos(8, 2, 8);
    private StationarySteamDemandTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (int heat : new int[]{0, 1, 2}) for (int count : new int[]{1, 2})
            for (int rpm : new int[]{-128, 0, 16, 64, 128, 256, 384, 480, 512, 640})
                for (int stock : new int[]{0, 1, 100}) {
                    String name = "steam_demand_world_heat_" + heat + "_engines_" + count + "_rpm_" + rpm + "_water_" + stock;
                    if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
                        tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test",
                            80, 0, true, h -> run(h, heat, count, rpm, stock)));
                }
        // Reuse the existing real-engine and pump integration tests in the
        // selectable steam suite, without rerunning unrelated machine tests.
        for (var type : new Class<?>[]{SteamEngineTests.class, PumpPhysicsTests.class})
            for (var method : type.getDeclaredMethods()) {
                var annotation = method.getAnnotation(GameTest.class);
                if (annotation == null || type == PumpPhysicsTests.class
                    && !method.getName().matches("(coasting|flywheel)PumpFeedsBoiler")) continue;
                String name = "steam_demand_existing_" + method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
                    tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test",
                        annotation.timeoutTicks(), 0, true, h -> {
                            try { method.invoke(null, h); }
                            catch (ReflectiveOperationException failure) {
                                Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation
                                    ? invocation.getCause() : failure;
                                if (cause instanceof RuntimeException runtime) throw runtime;
                                throw new IllegalStateException("Existing steam integration failed", cause);
                            }
                        }));
            }
        return tests;
    }
    private static void run(GameTestHelper h, int heat, int count, int rpm, int stock) {
        for (int x = ((int) h.getBounds().minX >> 4) - 1; x <= ((int) h.getBounds().maxX >> 4) + 1; x++)
            for (int z = ((int) h.getBounds().minZ >> 4) - 1; z <= ((int) h.getBounds().maxZ >> 4) + 1; z++)
                h.getLevel().setChunkForced(x, z, true);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            h.setBlock(TANK.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
        var shafts = new PoweredShaftBlockEntity[count];
        for (int i = 0; i < count; i++) {
            var pos = TANK.south(2).east(i);
            h.setBlock(pos, AllBlocks.STEAM_ENGINE.getDefaultState().setValue(SteamEngineBlock.FACE, AttachFace.WALL)
                .setValue(SteamEngineBlock.FACING, Direction.SOUTH));
            h.setBlock(pos.south(2), AllBlocks.POWERED_SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            shafts[i] = h.getBlockEntity(pos.south(2));
        }
        h.runAfterDelay(16, () -> {
            FluidTankBlockEntity tank = h.getBlockEntity(TANK); var boiler = tank.boiler;
            h.assertTrue(tank.getTotalTankSize() == 8 && boiler.attachedEngines == count, "Native world multiblock/engine association is ready");
            boiler.activeHeat = heat; boiler.passiveHeat = heat == 0; boiler.needsHeatLevelUpdate = false;
            for (var shaft : shafts) { shaft.setSpeed(rpm); ElasticKinetics.state(shaft).physicalSpeed = rpm; }
            h.runAfterDelay(1, () -> {
                SteamBoilerWater.begin(tank);
                double level = heat == 0 ? .125 : heat;
                double expected = count * 10 * Math.min(Math.min(1, level / count), Math.max(16, Math.abs(rpm)) / 256.0)
                    * Math.clamp((512 - rpm) / 64.0, 0, 1);
                h.assertTrue(Math.abs(BoilerAdmission.demand(boiler) - expected) < 1e-7,
                    "Stationary boiler demand follows the same independent RPM and cylinder power formula: actual="
                        + BoilerAdmission.demand(boiler) + " expected=" + expected);
                var handler = boiler.createHandler(); var water = new FluidStack(Fluids.WATER, stock);
                double carry = ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater();
                int requested = (int) Math.ceil(Math.max(0, expected - carry));
                int simulate = handler.fill(water, FluidAction.SIMULATE);
                h.assertTrue(simulate == Math.min(stock, requested)
                    && ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater() == carry,
                    "Simulation cannot reserve or charge water");
                int accepted = handler.fill(water, FluidAction.EXECUTE);
                h.assertTrue(accepted == simulate, "Execution accepts exactly the simulated amount");
                double supplied = Math.min(expected, carry + accepted);
                double fraction = expected > 0 ? supplied / expected : 0;
                h.assertTrue(Math.abs(BoilerAdmission.fraction(boiler) - fraction) < 1e-8,
                    "Zero/partial water produces only its paid fraction of cylinder pressure and power");
                float reading = boiler.waterSupply;
                double paidCarry = ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater();
                for (var shaft : shafts) {
                    var curve = SourcePhysics.steamCurve(shaft);
                    h.assertTrue(Math.abs(Math.abs(curve.stallTorque()) - 1024 * fraction) < 1e-7,
                        "Actual stationary input curve scales finite torque by paid water, including startup");
                    SourcePhysics.steamIntake(shaft, rpm);
                }
                h.assertTrue(boiler.waterSupply == reading && ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater() == paidCarry,
                    "Inspecting curves and render snapshots cannot advance a payment cycle or debit water");
                tank.setController(h.absolutePos(new BlockPos(20, 2, 20)));
                h.assertTrue(handler.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE) == 0,
                    "A held capability cannot feed a tank that is no longer the boiler controller");
                tank.setController(tank.getBlockPos());
                int second = handler.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE);
                double delivered = Math.min(expected, carry + accepted + second);
                h.assertTrue(second == Math.max(0, requested - accepted) && handler.fill(water, FluidAction.EXECUTE) == 0,
                    "Multiple ports and repeated execution share one bounded request");
                double remaining = ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater();
                h.assertTrue(Math.abs(delivered - boiler.waterSupply) < 1e-6
                    && Math.abs(carry + accepted + second - delivered - remaining) < 1e-8 && remaining >= 0 && remaining < 1,
                    "Paid fractional water is conserved outside liquid storage");
                h.assertTrue(handler.getFluidInTank(0).isEmpty() && handler.drain(100, FluidAction.EXECUTE).isEmpty(),
                    "Boiler steam feed remains non-drainable and exposes no liquid inventory");
                var snapshot = new BoilerData(); snapshot.read(boiler.write(), 8);
                h.assertTrue(BoilerAdmission.demand(snapshot) == expected && BoilerAdmission.fraction(snapshot) == BoilerAdmission.fraction(boiler),
                    "Current world and mounted snapshots share demand and paid fraction");
                h.succeed();
            });
        });
    }
}

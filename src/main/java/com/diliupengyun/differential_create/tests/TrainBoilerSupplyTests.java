package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.SteamGovernor;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Paid cylinder demand, shared static curves, and actual mounted inventory debits. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainBoilerSupplyTests {
    private TrainBoilerSupplyTests() {}
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 160, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (int heat : new int[]{0, 1, 2, 4}) for (boolean two : new boolean[]{false, true})
            for (double valve : new double[]{0, .001, .03, .1, .5, 1}) for (String mode : new String[]{"drive", "brake", "neutral", "idle"})
                add(tests, "unified_boiler_heat_" + heat + "_engines_" + (two ? 2 : 1) + "_valve_"
                    + Double.toString(valve).replace('.', '_') + "_" + mode, h -> tiers(h, heat, two, valve, mode));
        for (int water : new int[]{0, 1, 9, 10, 11, 19, 20, 21, 39, 40, 41}) for (boolean two : new boolean[]{false, true})
            for (double valve : new double[]{.03, .5, 1})
                add(tests, "unified_boiler_water_" + water + "_engines_" + (two ? 2 : 1) + "_valve_"
                    + Double.toString(valve).replace('.', '_'), h -> boundary(h, water, two, valve));
        add(tests, "unified_boiler_parked_fuel_expiry", TrainBoilerSupplyTests::parked);
        add(tests, "unified_boiler_paid_carry_reload", TrainBoilerSupplyTests::carry);
        for (var heat : HeatLevel.values())
            add(tests, "unified_boiler_burner_" + heat.getSerializedName(), h -> burner(h, heat));
        add(tests, "unified_boiler_native_world_flow", TrainBoilerSupplyTests::worldFlow);
        for (int heat : new int[]{0, 1, 4}) for (boolean two : new boolean[]{false, true})
            for (double valve : new double[]{.03, .5, 1}) for (int rpm : new int[]{-128, 0, 16, 64, 128, 256, 384, 480, 512, 640})
                add(tests, "steam_demand_train_heat_" + heat + "_engines_" + (two ? 2 : 1)
                    + "_valve_" + valve + "_rpm_" + rpm, h -> rotorFlow(h, heat, two, valve, rpm));
        return tests;
    }
    private static void create(GameTestHelper h, int heat, boolean two, Consumer<TrainTestFixture> run) {
        TrainTestFixture.create(h, "flat", false, false, two, setup -> {
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                for (int y = 2; y < 4; y++) setup.setBlock(TrainTestFixture.BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
                var pos = TrainTestFixture.BOILER.offset(x, -1, z);
                if (heat == 0 || x * 2 + z < heat) {
                    var state = AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL,
                        heat == 0 ? HeatLevel.SMOULDERING : HeatLevel.KINDLED);
                    setup.setBlock(pos, state);
                    BlazeBurnerBlockEntity burner = setup.getBlockEntity(pos); burner.isCreative = true;
                }
            }
            if (two) SteamBogey.install(setup.getBlockEntity(TrainTestFixture.WHEEL.south(6)));
        }, run);
    }
    private static BoilerData reference(int heat, int engines, float supply) {
        var data = new CompoundTag(); data.putInt("ActiveHeat", heat); data.putBoolean("PassiveHeat", heat == 0);
        data.putInt("Engines", engines); data.putFloat("Supply", supply);
        var boiler = new BoilerData(); boiler.read(data, 16); return boiler;
    }
    private static double expectedFlow(double level, int engines, double opening, double rpm) {
        return 10 * opening * Math.min(Math.min(1, level / engines), Math.max(16, Math.abs(rpm)) / 256)
            * Math.clamp((512 - Math.abs(rpm)) / 64, 0, 1);
    }
    private static void tiers(GameTestHelper h, int heat, boolean two, double valve, String mode) {
        create(h, heat, two, f -> {
            var train = f.train; var steam = TrainPhysics.carriage(train.carriages.getFirst());
            h.assertTrue(steam.layout.boilers.values().iterator().next().size() == 16 && steam.layout.motors.size() == (two ? 2 : 1),
                "Native assembly captures the same four-tier boiler with one/two installed engines");
            train.throttle = valve;
            if (mode.equals("neutral")) TrainPhysics.shift(train, 0);
            int water = f.water(), coal = f.items(Items.COAL);
            boolean driven = mode.equals("drive") || mode.equals("neutral");
            double opening = driven ? valve : 0, thermal = heat == 0 ? .125 : heat, totalFlow = 0;
            double conversion = TrainMechanics.TORQUE_SCALE * (2 * Math.PI / 60);
            double rated = SourcePhysics.steamCurve(steam.layout.motors.getFirst().capacity(), 1, 1, 1).powerLimit() * conversion;
            for (int i = 0; i < 20; i++) {
                double flow = steam.engines.values().stream().mapToDouble(e -> expectedFlow(thermal, steam.engines.size(), opening, e.rpm())).sum();
                totalFlow += flow;
                TrainPhysics.manual(train, driven ? 1 : 0, mode.equals("brake")); train.tick(h.getLevel());
                var actual = steam.boilers.values().iterator().next();
                h.assertTrue(Math.abs(actual.waterSupply - flow) < 1e-5 && SourcePhysics.steamLevel(actual, 16) == (flow > 0 ? thermal : 0)
                    && BoilerAdmission.opening(actual) == opening,
                    "Actual cylinder flow follows RPM, admission, thermal allocation and regulator closure");
                double expected = flow > 0 ? rated * Math.min(1, thermal / steam.engines.size()) * opening : 0;
                for (var engine : steam.engines.values())
                    h.assertTrue(Math.abs(engine.power - expected) < 1e-7 && engine.power <= rated * opening + 1e-7,
                        "Every engine shares native boiler power and stays below one static engine's rated power at its admission");
                for (var rotor : TrainStatePayload.rotors(steam)) {
                    var engine = steam.engines.get(rotor.pos());
                    double supplied = expected / rated * SteamGovernor.opening(engine.stallTorque, engine.rpm(), engine.governor, engine.governorRange);
                    h.assertTrue(Math.abs(rotor.steamSupply() - supplied) < 1e-7, "Audio intake includes actual regulator closure");
                }
                h.assertTrue(Math.abs(SourcePhysics.steamPeakPower(steam.layout.motors.getFirst().capacity(), actual, 16)
                    * conversion - expected * steam.engines.size()) < 1e-7, "Boiler diagnostics use the same bounded admitted power as the engines");
            }
            double paid = water - f.water() - steam.water.values().stream().mapToDouble(Double::doubleValue).sum();
            h.assertTrue(Math.abs(paid - totalFlow) < 1e-8 && f.items(Items.COAL) == coal,
                "Fractional admitted water is paid once and creative heaters do not take cargo fuel");
            if (!driven || valve == 0 || mode.equals("neutral")) h.assertTrue(train.speed == 0, "Boiler feed never drives closed/disconnected wheels");
            f.finish();
        });
    }
    private static void boundary(GameTestHelper h, int water, boolean two, double valve) {
        create(h, 4, two, f -> {
            var train = f.train; var steam = TrainPhysics.carriage(train.carriages.getFirst());
            var fluids = train.carriages.getFirst().storage.getFluids(); fluids.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            var reserve = fluids.storages.entrySet().stream().filter(e -> !steam.layout.boilers.containsKey(e.getKey()))
                .findFirst().orElseThrow().getValue();
            reserve.fill(new FluidStack(Fluids.WATER, water), FluidAction.EXECUTE);
            train.throttle = valve;
            TrainPhysics.shift(train, 0); TrainPhysics.manual(train, 1, false); train.tick(h.getLevel());
            double demand = .625 * valve * steam.engines.size();
            double delivered = Math.min(water, demand); var actual = steam.boilers.values().iterator().next();
            var nativeBoiler = reference(4, two ? 2 : 1, (float) delivered);
            BoilerAdmission.set(nativeBoiler, valve); BoilerAdmission.supply(nativeBoiler, demand, delivered);
            double paid = water - f.water() - steam.water.values().stream().mapToDouble(Double::doubleValue).sum();
            h.assertTrue(actual.waterSupply == (float) delivered && SourcePhysics.steamLevel(actual, 16) == SourcePhysics.steamLevel(nativeBoiler, 16)
                && Math.abs(paid - delivered) < 1e-8, "Finite water meets the native tier at the chosen admission without unpaid or duplicated supply");
            if (water == 0) h.assertTrue(TrainPhysics.state(train).engineRpm == 0, "An unpaid engine cannot start");
            double rated = SourcePhysics.steamCurve(steam.layout.motors.getFirst().capacity(), 1, 1, 1).powerLimit();
            double admitted = delivered > 0 ? rated * valve * delivered / demand : 0;
            double supplied = Double.isFinite(admitted) ? Math.clamp(admitted / rated, 0, 1) : 0;
            for (var rotor : TrainStatePayload.rotors(steam))
                h.assertTrue(Math.abs(rotor.steamSupply() - supplied) < 1e-7,
                    "Water exhaustion immediately closes audio intake rather than trusting coasting RPM");
            f.finish();
        });
    }
    private static void parked(GameTestHelper h) {
        create(h, 4, false, f -> {
            var steam = TrainPhysics.carriage(f.train.carriages.getFirst());
            steam.burners.values().forEach(b -> { b.creative = false; b.fuel = 1; b.remaining = 40; });
            int coal = f.items(Items.COAL), water = f.water();
            for (int i = 0; i < 20; i++) { TrainPhysics.manual(f.train, 0, false); f.train.tick(h.getLevel()); }
            var boiler = steam.boilers.values().iterator().next();
            h.assertTrue(f.items(Items.COAL) == coal && steam.burners.values().stream().allMatch(b -> b.remaining == 20)
                && boiler.activeHeat == 4 && !boiler.passiveHeat && boiler.waterSupply == 0 && f.water() == water && f.train.speed == 0,
                "Parking stops water and fresh fuel while native fading burners still supply one heat level each");
            for (int i = 0; i < 40; i++) { TrainPhysics.manual(f.train, 0, false); f.train.tick(h.getLevel()); }
            h.assertTrue(f.items(Items.COAL) == coal && steam.burners.values().stream().allMatch(b -> b.fuel == 0 && b.remaining == 0)
                && boiler.activeHeat == 0 && boiler.passiveHeat && boiler.waterSupply == 0 && f.water() == water,
                "Parked burners finish their existing fuel without restarting from cargo");
            f.finish();
        });
    }
    private static void carry(GameTestHelper h) {
        create(h, 4, false, f -> {
            var steam = TrainPhysics.carriage(f.train.carriages.getFirst()); var pos = steam.layout.boilers.keySet().iterator().next();
            steam.water.put(pos, .375); int water = f.water();
            TrainPhysics.shift(f.train, 0);
            f.tick(1, false);
            h.assertTrue(f.water() == water - 1 && steam.water.get(pos) == .75, "Starting flow spends paid carry and only one integer mB");
            var snapshot = CarriageSteamState.read(steam.write(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(snapshot.water.get(pos) == .75 && snapshot.boilers.get(pos).waterSupply == .625
                && snapshot.boilers.get(pos).activeHeat == 4, "Reload retains paid carry and actual boiler diagnostics");
            var block = f.entity().getContraption().getBlocks().get(pos);
            h.assertTrue(block.nbt().getCompound("Boiler").getFloat("Supply") == .625f && block.nbt().getCompound("Boiler").getInt("ActiveHeat") == 4,
                "Disassembly snapshots contain actual supplied water and heat instead of assembly-time diagnostics");
            f.train.throttle = .03; f.tick(1, false);
            double buffered = steam.water.get(pos);
            snapshot = CarriageSteamState.read(steam.write(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(snapshot.water.get(pos) == buffered && buffered >= 0 && buffered < 1
                && BoilerAdmission.opening(snapshot.boilers.get(pos)) == .03
                && BoilerAdmission.demand(snapshot.boilers.get(pos)) == BoilerAdmission.demand(steam.boilers.get(pos))
                && BoilerAdmission.fraction(snapshot.boilers.get(pos)) == 1,
                "Partial admission and previously paid fractional water survive reload together");
            int stock = f.water(); f.tick(0, false);
            h.assertTrue(f.water() == stock && steam.water.get(pos) == buffered && BoilerAdmission.opening(steam.boilers.get(pos)) == 0,
                "Closing admission preserves unused paid water without a final extra debit");
            f.finish();
        });
    }
    private static void burner(GameTestHelper h, HeatLevel heat) {
        TrainTestFixture.create(h, "flat", false, false, false, setup -> {
            setup.setBlock(TrainTestFixture.BOILER.below(), net.minecraft.world.level.block.Blocks.AIR);
            setup.setBlock(TrainTestFixture.BOILER.below(), AllBlocks.BLAZE_BURNER.getDefaultState()
                .setValue(BlazeBurnerBlock.HEAT_LEVEL, heat));
            if (heat == HeatLevel.NONE) return;
            BlazeBurnerBlockEntity burner = setup.getBlockEntity(TrainTestFixture.BOILER.below());
            var tag = new CompoundTag();
            tag.putInt("fuelLevel", heat == HeatLevel.SEETHING ? 2 : heat.isAtLeast(HeatLevel.FADING) ? 1 : 0);
            tag.putInt("burnTimeRemaining", heat == HeatLevel.FADING ? 100 : heat.isAtLeast(HeatLevel.KINDLED) ? 2000 : 0);
            burner.loadWithComponents(tag, h.getLevel().registryAccess());
        }, f -> {
            var steam = TrainPhysics.carriage(f.train.carriages.getFirst());
            var mounted = steam.burners.values().stream().findFirst().orElse(null);
            int heatScore = heat == HeatLevel.SEETHING ? 2 : heat.isAtLeast(HeatLevel.FADING) ? 1 : 0;
            int coal = f.items(Items.COAL), water = f.water();
            for (int i = 0; i < 10; i++) { TrainPhysics.manual(f.train, 0, false); f.train.tick(h.getLevel()); }
            var actual = steam.boilers.values().iterator().next();
            h.assertTrue((heat == HeatLevel.NONE ? mounted == null : mounted != null && mounted.heat() == heat)
                && actual.activeHeat == heatScore && actual.waterSupply == 0 && f.water() == water
                && f.items(Items.COAL) == coal, "All native burner states retain their heat, water tier and fuel behavior after assembly");
            if (heat == HeatLevel.NONE) {
                f.tick(10, false);
                h.assertTrue(actual.activeHeat == 0 && !actual.passiveHeat && actual.waterSupply == 0 && f.items(Items.COAL) == coal,
                    "An empty cage cannot provide passive heat or consume cargo fuel");
            }
            f.finish();
        });
    }
    private static void worldFlow(GameTestHelper h) {
        TrainIntegrationTests.structure(h, 4000, false);
        var nativeBoiler = reference(1, 1, .3f);
        BoilerAdmission.supply(nativeBoiler, .3, .3);
        h.assertTrue(SourcePhysics.steamLevel(nativeBoiler, 16) == 1,
            "A paid low-RPM cylinder can use full pressure below the old ten-mB threshold");
        var restored = new BoilerData(); restored.read(nativeBoiler.write(), 16);
        h.assertTrue(BoilerAdmission.fraction(restored) == 1 && BoilerAdmission.demand(restored) == .3,
            "Current stationary/mounted boiler data shares exact demand and paid supply fields");
        h.succeed();
    }
    private static void rotorFlow(GameTestHelper h, int heat, boolean two, double valve, int rpm) {
        create(h, heat, two, f -> {
            var steam = TrainPhysics.carriage(f.train.carriages.getFirst());
            TrainPhysics.shift(f.train, 0); f.train.throttle = valve;
            for (var engine : steam.engines.values()) engine.omega = rpm * Math.PI / 30;
            int water = f.water();
            double thermal = heat == 0 ? .125 : heat;
            double expected = steam.engines.size() * expectedFlow(thermal, steam.engines.size(), valve, rpm);
            f.tick(1, false);
            var boiler = steam.boilers.values().iterator().next();
            double paid = water - f.water() - steam.water.values().stream().mapToDouble(Double::doubleValue).sum();
            h.assertTrue(Math.abs(paid - expected) < 1e-8 && Math.abs(boiler.waterSupply - expected) < 1e-5,
                "Real mounted debits match independent per-engine RPM/power/regulator expectations");
            h.assertTrue(f.train.speed == 0, "Neutral resource use cannot propel wheels");
            var ref = reference(heat, steam.engines.size(), (float) expected);
            BoilerAdmission.set(ref, valve); BoilerAdmission.supply(ref, expected, expected);
            for (var motor : steam.layout.motors) {
                var curve = SourcePhysics.admittedCurve(motor.capacity(), ref, 16, rpm < 0 ? -1 : 1);
                var engine = steam.engines.get(motor.pos());
                double power = Double.isFinite(curve.powerLimit()) ? curve.powerLimit() : 0;
                h.assertTrue(Math.abs(engine.stallTorque - curve.stallTorque() * TrainMechanics.TORQUE_SCALE) < 1e-8
                    && Math.abs(engine.power - power * TrainMechanics.TORQUE_SCALE * Math.PI / 30) < 1e-7,
                    "Static and mounted engines receive identical curve parameters at the same paid demand");
            }
            f.finish();
        });
    }
}

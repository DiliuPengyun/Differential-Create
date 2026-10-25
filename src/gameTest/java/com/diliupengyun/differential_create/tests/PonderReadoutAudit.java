package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.kinetics.ElasticKinetics;
import com.diliupengyun.differential_create.kinetics.LoadFactor;
import com.diliupengyun.differential_create.kinetics.LoadGaugeBehaviour;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;
import com.diliupengyun.differential_create.kinetics.NetworkDynamics;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeRangeBehaviour;
import com.diliupengyun.differential_create.kinetics.SpeedGaugeScale;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.content.kinetics.gauge.GaugeBlockEntity;
import com.simibubi.create.content.kinetics.gauge.SpeedGaugeBlockEntity;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Readout consistency on every sampled frame, plus independent physical response fixtures. */
final class PonderReadoutAudit {
    private final List<String> failures = new ArrayList<>();
    private final Map<FlapDisplaySection, Integer> stableFlaps = new IdentityHashMap<>();
    private boolean cuttingLoad, releasedLoad, balancedFan, coasting, displayStatus;

    void sample(PonderScene scene) {
        var level = scene.getWorld();
        for (var be : level.getBlockEntities()) {
            if (be instanceof GaugeBlockEntity gauge) {
                double rpm = NetworkDynamics.preciseSpeed(gauge);
                float expected;
                int signal;
                if (gauge instanceof StressGaugeBlockEntity load) {
                    var reading = LoadGaugeBehaviour.reading(load);
                    expected = LoadGaugeScale.position(reading);
                    signal = LoadGaugeScale.signal(reading);
                    check(gauge.color == LoadGaugeScale.color(reading), "load colour disagrees with its reading", scene);
                    if (scene.getId().getPath().equals("stressometer")) {
                        boolean cutting = false;
                        for (var b : level.getBlockEntities())
                            cutting |= b instanceof SawBlockEntity saw && saw.inventory.remainingTime > 0 && !saw.inventory.isEmpty();
                        cuttingLoad |= cutting && LoadGaugeScale.available(reading) && reading.fraction() > .9;
                        releasedLoad |= cuttingLoad && !cutting && LoadGaugeScale.available(reading) && reading.fraction() == 0;
                    }
                    if (scene.getId().getPath().equals("load_gauge")) {
                        balancedFan |= Math.abs(rpm) > 1 && LoadGaugeScale.available(reading)
                            && Math.abs(reading.fraction() - 1) < .001 && signal == 8;
                        coasting |= Math.abs(rpm) > 1 && reading.status() == LoadFactor.Status.NO_POWER && signal == 0;
                    }
                } else if (gauge instanceof SpeedGaugeBlockEntity speed) {
                    int range = SpeedGaugeRangeBehaviour.rangeOf(speed);
                    expected = SpeedGaugeScale.fraction(rpm, range);
                    signal = SpeedGaugeScale.signal(rpm, range);
                } else continue;
                check(Math.abs(gauge.dialTarget - expected) < 1e-6, "scripted dial replaces the physical sample", scene);
                check(gauge.getBlockState().getAnalogOutputSignal(level, gauge.getBlockPos()) == signal,
                    "gauge's redstone output disagrees with its reading", scene);
                for (var side : Direction.Plane.HORIZONTAL) {
                    var pos = gauge.getBlockPos().relative(side);
                    if (!(level.getBlockEntity(pos) instanceof ComparatorBlockEntity comparator)) continue;
                    var state = level.getBlockState(pos);
                    var facing = state.getValue(ComparatorBlock.FACING);
                    if (!pos.relative(facing).equals(gauge.getBlockPos())) continue;
                    check(comparator.getOutputSignal() == signal && state.getValue(BlockStateProperties.POWERED) == (signal > 0),
                        "comparator was animated independently of its gauge", scene);
                    var wire = level.getBlockState(pos.relative(facing.getOpposite()));
                    if (wire.is(Blocks.REDSTONE_WIRE))
                        check(wire.getValue(BlockStateProperties.POWER) == signal, "output wire retains a scripted signal", scene);
                }
            }
            if (scene.getId().getPath().equals("display_link") && be instanceof FlapDisplayBlockEntity board)
                for (var line : board.getLines()) for (var section : line.getSections()) {
                    var text = section.getText();
                    if (text == null) continue;
                    check(!text.getString().equals("1024 su"), "display link retains the old nominal stress sample", scene);
                    boolean status = text.getString().equals(LoadGaugeBehaviour.text(LoadFactor.Reading.NO_POWER).getString());
                    displayStatus |= status;
                    if (status && stableFlaps.merge(section, 1, Integer::sum) > 4) {
                        try {
                            var age = FlapDisplaySection.class.getDeclaredField("spinningTicks");
                            age.setAccessible(true);
                            check(age.getInt(section) >= 3, "unchanged measurement keeps restarting display flaps", scene);
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    } else if (!status) stableFlaps.remove(section);
                }
        }
    }

    void verify(PonderScene scene) {
        switch (scene.getId().toString()) {
            case "create:stressometer" -> check(cuttingLoad && releasedLoad, "cutting and idle must produce different real loads", scene);
            case "differentialcreate:load_gauge" -> check(balancedFan && coasting, "missing real balance or unpowered coasting", scene);
            case "create:display_link" -> check(displayStatus, "unpowered gauge never publishes its actual status", scene);
            default -> {}
        }
        if (!failures.isEmpty()) throw new AssertionError("Ponder readout/physics mismatch: " + failures);
    }

    private void check(boolean condition, String message, PonderScene scene) {
        if (!condition && failures.size() < 12)
            failures.add(scene.getId() + " tick " + scene.getCurrentTime() + ": " + message);
    }

    static void verifyPhysics() {
        var level = new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
        var physics = PonderKinetics.of(level);
        physics.useExplicitSources();
        var inlet = new BlockPos(0, 1, 0);
        var gaugePos = inlet.east();
        level.setBlock(inlet, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X), 3);
        level.setBlock(gaugePos, AllBlocks.STRESSOMETER.getDefaultState()
            .setValue(GaugeBlock.FACING, Direction.UP).setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, true), 3);
        level.setBlock(inlet.east(2), AllBlocks.ENCASED_FAN.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        var gauge = (StressGaugeBlockEntity) level.getBlockEntity(gaugePos);
        physics.supply(level, inlet, new SourcePhysics.Curve(64, .25));
        for (int i = 0; i < 30; i++) level.tick();
        var accelerating = physics.loadReading(gauge);
        double first = gauge.getSpeed();
        require(accelerating.status() == LoadFactor.Status.AVAILABLE && accelerating.fraction() > 0
            && accelerating.fraction() < 1, "starting fan must leave power for acceleration");
        level.tick();
        require(gauge.getSpeed() > first, "underloaded simple fan assembly did not accelerate");
        for (int i = 0; i < 800; i++) level.tick();
        require(Math.abs(physics.loadReading(gauge).fraction() - 1) < .001,
            "steady fan must approach 100%, not its old nominal stress percentage");
        double settled = gauge.getSpeed();
        physics.supply(level, inlet, new SourcePhysics.Curve(1, 0, Double.POSITIVE_INFINITY));
        for (int i = 0; i < 20; i++) level.tick();
        require(physics.loadReading(gauge).fraction() > 1 && gauge.getSpeed() < settled,
            "positive but insufficient drive must report overload while losing speed");
        physics.supply(level, inlet, new SourcePhysics.Curve(0, 0));
        level.tick();
        require(physics.loadReading(gauge).status() == LoadFactor.Status.NO_POWER && gauge.getSpeed() > 1,
            "stored kinetic energy must not count as an active power source");
        gauge.dialTarget = .9f; gauge.color = 0xFFFFFF;
        physics.refreshInstruments(level);
        require(gauge.dialTarget == LoadGaugeScale.REST && gauge.color == LoadGaugeScale.IDLE_COLOR,
            "a scripted pointer must not survive physical refresh");
        level.setBlock(inlet.east(2), Blocks.AIR.defaultBlockState(), 3);
        physics.supply(level, inlet, new SourcePhysics.Curve(64, .25));
        for (int i = 0; i < 800; i++) level.tick();
        require(physics.loadReading(gauge).fraction() == 0 && Math.abs(gauge.getSpeed() - 64) < .001,
            "unloaded equilibrium is 0% without perpetual acceleration");
        physics.reset();
        require(physics.loadReading(gauge).status() == LoadFactor.Status.NO_POWER, "rewind must clear cached samples");
        verifyCrushingReaction();
        DifferentialCreate.LOGGER.info("DC_PONDER_READOUT_PHYSICS: acceleration, balance, overload deceleration, coasting, zero-load equilibrium and stale-script rejection passed");
    }

    /** A material contact removes relative energy and preserves the pair's angular momentum. */
    private static void verifyCrushingReaction() {
        for (double[] targets : new double[][]{{64, 32}, {-64, -32}, {64, -32}, {32, 32}}) {
            var level = new PonderLevel(BlockPos.ZERO, Minecraft.getInstance().level);
            var physics = PonderKinetics.of(level);
            physics.useExplicitSources();
            var a = new BlockPos(1, 1, 2);
            var b = a.east(2);
            for (var pos : new BlockPos[]{a, b})
                level.setBlock(pos, AllBlocks.CRUSHING_WHEEL.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Z), 3);
            physics.boundary(level, a, (float) targets[0]);
            physics.boundary(level, b, (float) targets[1]);
            for (int i = 0; i < 300; i++) level.tick();
            level.setBlock(a.east(), AllBlocks.CRUSHING_WHEEL_CONTROLLER.getDefaultState(), 3);
            var controller = (com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity)
                level.getBlockEntity(a.east());
            controller.inventory.insertItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE), false);
            controller.inventory.remainingTime = 1000;
            var pair = com.diliupengyun.differential_create.kinetics.CrushingKinetics.pair(controller);
            // Ponder publishes float RPM to Create for rendering; conservation
            // checks must use the solver's double state, before that conversion.
            double beforeA = ElasticKinetics.state(pair.a()).physicalSpeed;
            double beforeB = ElasticKinetics.state(pair.b()).physicalSpeed;
            double inertia = com.diliupengyun.differential_create.kinetics.InertiaRegistry.getInertia(pair.a());
            double dt = com.diliupengyun.differential_create.kinetics.RotationMath.TICK_MINUTES;
            double reaction = Math.copySign(pair.torque(), beforeA - beforeB);
            if (beforeA == beforeB) reaction = 0;
            physics.supply(level, a, new SourcePhysics.Curve(0, 0));
            physics.supply(level, b, new SourcePhysics.Curve(0, 0));
            level.tick();
            double afterA = ElasticKinetics.state(pair.a()).physicalSpeed;
            double afterB = ElasticKinetics.state(pair.b()).physicalSpeed;
            require(Math.abs(afterA - (beforeA - reaction * dt / inertia)) < 1e-6
                    && Math.abs(afterB - (beforeB + reaction * dt / inertia)) < 1e-6,
                "crushing preview must use equal/opposite reactions, including same-way power return: "
                    + beforeA + "/" + beforeB + " -> " + afterA + "/" + afterB + "; torque=" + reaction
                    + ", inertia=" + inertia + ", occupied=" + pair.active());
            require(Math.abs(afterA + afterB - beforeA - beforeB) < 1e-6, "crushing creates or destroys pair momentum");
            double removed = .5 * inertia * (beforeA * beforeA + beforeB * beforeB - afterA * afterA - afterB * afterB);
            double work = reaction * ((beforeA - beforeB + afterA - afterB) / 2) * dt;
            require(Math.abs(removed - work) < 1e-6, "crushing work disagrees with kinetic energy removed");
            if (beforeA > beforeB && beforeB > 0)
                require(physics.loadReading(pair.b()).status() == LoadFactor.Status.AVAILABLE
                    && physics.loadReading(pair.b()).fraction() == 0, "slower same-way wheel must receive returned power");
        }
        DifferentialCreate.LOGGER.info("DC_PONDER_CRUSHING_BALANCE: same-way, reverse, counterrotation and synchronous pairs conserve momentum and pay relative work");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

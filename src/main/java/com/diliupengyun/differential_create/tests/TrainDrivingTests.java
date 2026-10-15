package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import io.netty.buffer.Unpooled;

/** Exercise Create's actual cab control method before finite-force rail travel. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainDrivingTests {
    private static final BlockPos CAB = new BlockPos(10, 4, 5), LEVER = new BlockPos(8, 4, 5);
    private static void add(List<TestFunction> cases, String name, Consumer<GameTestHelper> run) {
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            cases.add(new TestFunction("train_driving", name, "differentialcreate_trains:kineticphysicstests.physics_test", 160, 0, true, run));
    }
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (int gear : new int[]{-1, 0, 1, 2, 3, 4, 5, TrainMechanics.REVERSE_GEAR})
            for (boolean inverted : new boolean[]{false, true}) for (int momentum : new int[]{-1, 0, 1})
                for (String keys : new String[]{"drive", "brake", "space", "both", "brake_space", "coast"})
                    add(tests, "train_driving_" + (gear < 0 ? "no_lever" : GearLeverBlock.label(gear))
                        + (inverted ? "_rear" : "_front") + "_" + momentum + "_" + keys,
                        h -> drive(h, gear, inverted, momentum, keys));
        add(tests, "train_driving_shift_keyboard_and_physical_order", TrainDrivingTests::gearOrder);
        add(tests, "train_driving_reverse_reload_and_sync", TrainDrivingTests::reload);
        for (boolean behind : new boolean[]{false, true})
            add(tests, "train_driving_npc_" + (behind ? "reverse" : "forward"), h -> npc(h, behind));
        add(tests, "train_driving_direction_change_keeps_momentum", TrainDrivingTests::directionChange);
        for (boolean inverted : new boolean[]{false, true}) for (int momentum : new int[]{-1, 0, 1})
            add(tests, "train_braking_equal_force_" + (inverted ? "rear" : "front") + "_" + momentum,
                h -> equalBraking(h, inverted, momentum));
        for (boolean inverted : new boolean[]{false, true})
            for (String op : new String[]{"rev", "closed_valve", "partial_valve", "no_water", "no_heat", "brake", "release", "reload", "shift_forward", "shift_reverse", "negative_input", "water_exhaust"})
                add(tests, "train_neutral_" + op + (inverted ? "_rear" : "_front"), h -> neutral(h, inverted, op));
        return tests;
    }
    static void create(GameTestHelper h, int gear, boolean inverted, Consumer<TrainTestFixture> run) {
        create(h, gear, inverted, setup -> {}, run);
    }
    static void create(GameTestHelper h, int gear, boolean inverted, Consumer<GameTestHelper> customise, Consumer<TrainTestFixture> run) {
        TrainTestFixture.create(h, "flat", false, false, setup -> {
            var chunk = new ChunkPos(setup.absolutePos(TrainTestFixture.WHEEL));
            // Driver tests must keep a cab loaded while moving either way.
            for (int x = -1; x <= 1; x++) for (int z = -2; z <= 1; z++)
                setup.getLevel().setChunkForced(chunk.x + x, chunk.z + z, true);
            setup.setBlock(CAB, AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.SOUTH));
            if (gear < 0) setup.setBlock(LEVER, Blocks.AIR);
            else setup.setBlock(LEVER, ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, gear));
            customise.accept(setup);
        }, f -> {
            var entity = f.entity(); var local = CAB.subtract(TrainTestFixture.WHEEL);
            var block = entity.getContraption().getBlocks().get(local);
            Direction facing = entity.getInitialOrientation().getCounterClockWise();
            entity.setBlock(local, new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                local, block.state().setValue(ControlsBlock.FACING, inverted ? facing.getOpposite() : facing), block.nbt()));
            run.accept(f);
        });
    }
    static Player player(TrainTestFixture f) {
        var player = f.h.makeMockPlayer(GameType.SURVIVAL);
        f.h.assertTrue(f.entity().startControlling(CAB.subtract(TrainTestFixture.WHEEL), player), "Native cab can be controlled");
        return player;
    }
    static void control(TrainTestFixture f, Player player, Collection<Integer> keys) {
        var local = CAB.subtract(TrainTestFixture.WHEEL);
        player.setPos(f.entity().toGlobalVector(Vec3.atCenterOf(local), 0));
        f.h.assertTrue(f.entity().control(local, keys, player), "Actual Create cab accepts driver input");
    }
    private static List<Integer> keys(String keys) {
        return switch (keys) {
            case "drive" -> List.of(0);
            case "brake" -> List.of(1);
            case "space" -> List.of(4);
            case "both" -> List.of(0, 1);
            case "brake_space" -> List.of(0, 1, 4);
            default -> List.of();
        };
    }
    private static void drive(GameTestHelper h, int gear, boolean inverted, int momentum, String keys) {
        create(h, gear, inverted, f -> {
            var player = player(f); var train = f.train; train.speed = momentum * .05;
            boolean brake = keys.equals("brake") || keys.equals("space") || keys.equals("both") || keys.equals("brake_space");
            int direction = keys.equals("drive") && gear != 0 ? (inverted ? -1 : 1) * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1) : 0;
            int water = f.water();
            for (int tick = 0; tick < 60; tick++) {
                control(f, player, keys(keys));
                var state = TrainPhysics.state(train);
                h.assertTrue(state.direction == direction && state.brake == brake, "W follows gear/cab orientation; S wins over W and space");
                h.assertTrue(state.neutralRev == (keys.equals("drive") && gear == 0), "Only W alone opens admission in neutral");
                boolean reversing = direction != 0 && train.speed * direction < -1e-5;
                train.tick(h.getLevel());
                h.assertTrue(state.controlsBraking == (brake || reversing), "Cab brake animation follows service/reversing brakes, excluding automatic stationary hold");
                double admission = keys.equals("drive") && !brake && !reversing ? train.throttle : 0;
                h.assertTrue(Math.abs(state.controlsAdmission - admission) < 1e-12, "Cab regulator animation follows effective admission, including neutral and release");
                if (gear == 0) h.assertTrue(Math.abs(train.speed) <= Math.abs(momentum * .05) + 1e-12,
                    "A disconnected, revving engine cannot accelerate the wheels in either direction");
                if (brake) h.assertTrue(momentum == 0 ? Math.abs(train.speed) < 1e-12 : train.speed * momentum >= -1e-12,
                    "Holding S cannot reverse the train after it reaches zero");
            }
            if (brake) h.assertTrue(Math.abs(train.speed) < 1e-8 && f.water() == water, "Service braking stops the wheels and closes water admission");
            else if (direction != 0) h.assertTrue(train.speed * direction > 0 && f.water() < water,
                "Selected drive direction uses real steam to propel the train: speed=" + train.speed + " direction=" + direction
                + " water=" + f.water() + " graph=" + train.graph + " failure=" + TrainPhysics.state(train).failure);
            else if (gear == 0 && keys.equals("drive")) h.assertTrue(TrainPhysics.state(train).engineRpm > 0 && f.water() < water,
                "W in neutral spins the input with actual boiler water, while leaving the wheels disconnected");
            else if (momentum == 0) h.assertTrue(train.speed == 0 && f.water() == water, "Idle engine inputs consume no water and create no motion");
            f.finish();
        });
    }
    private static void equalBraking(GameTestHelper h, boolean inverted, int momentum) {
        create(h, 1, inverted, f -> {
            var player = player(f); var carriage = f.train.carriages.getFirst();
            var snapshot = TrainPhysics.carriage(carriage).write(h.getLevel().registryAccess());
            double speed = Double.NaN;
            for (var keys : List.of(List.of(1), List.of(4), List.of(1, 4))) {
                ((CarriageSteamState.Holder) carriage).differentialcreate$steam(CarriageSteamState.read(snapshot, h.getLevel().registryAccess()));
                f.train.speed = momentum * .1;
                control(f, player, keys);
                h.assertTrue(TrainPhysics.state(f.train).brake && TrainPhysics.state(f.train).direction == 0,
                    "S, space and both close admission and request the same service brake");
                f.train.tick(h.getLevel());
                if (Double.isNaN(speed)) speed = f.train.speed;
                else h.assertTrue(Math.abs(f.train.speed - speed) < 1e-12,
                    "S, space and both produce equal finite braking impulses: " + f.train.speed + " != " + speed);
            }
            f.finish();
        });
    }
    private static void neutral(GameTestHelper h, boolean inverted, String op) {
        create(h, 0, inverted, setup -> {
            if (op.equals("no_heat")) setup.setBlock(TrainTestFixture.BOILER.below(), Blocks.AIR);
        }, f -> {
            var player = player(f); var train = f.train; var steam = TrainPhysics.carriage(train.carriages.getFirst());
            var reserve = train.carriages.getFirst().storage.getFluids().storages.entrySet().stream()
                .filter(entry -> !steam.layout.boilers.containsKey(entry.getKey())).findFirst().orElseThrow().getValue();
            if (op.equals("closed_valve")) train.throttle = 0;
            if (op.equals("partial_valve")) train.throttle = .1;
            if (op.equals("no_water") || op.equals("water_exhaust")) {
                reserve.drain(Integer.MAX_VALUE, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                if (op.equals("water_exhaust")) reserve.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            }
            if (op.equals("negative_input")) steam.engines.values().forEach(engine -> engine.omega = -10);
            int water = f.water(); double start = train.carriages.getFirst().getLeadingPoint().position;
            double requested = 0;
            for (int i = 0; i < 60; i++) {
                control(f, player, op.equals("brake") ? List.of(0, 1) : List.of(0)); train.tick(h.getLevel());
                h.assertTrue(train.speed == 0 && train.carriages.getFirst().getLeadingPoint().position == start,
                    "Neutral admission never moves the parked carriage or its travelling points");
                requested += steam.boilers.values().stream().mapToDouble(b -> b.waterSupply).sum();
            }
            double rpm = TrainPhysics.state(train).engineRpm;
            if (op.equals("no_water") || op.equals("no_heat") || op.equals("closed_valve") || op.equals("brake"))
                h.assertTrue(rpm == 0 && f.water() == water,
                    "No supply or closed admission creates neither rotation nor water consumption");
            else if (op.equals("water_exhaust")) {
                h.assertTrue(rpm > 0 && f.water() == 0 && steam.engines.values().stream().allMatch(e -> e.power == 0),
                    "One paid mB can start the input; after exhaustion only stored rotation remains");
                reserve.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 400),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                for (int i = 0; i < 20; i++) { control(f, player, List.of(0)); train.tick(h.getLevel()); }
                h.assertTrue(TrainPhysics.state(train).engineRpm > rpm && train.speed == 0, "Actual resupply restores neutral engine acceleration");
            }
            else {
                h.assertTrue(rpm > 0 && rpm <= 513 && f.water() < water, "Steam spins the neutral input under its governor with real resource use: " + rpm);
                if (op.equals("partial_valve")) {
                    double buffered = steam.water.values().stream().mapToDouble(Double::doubleValue).sum();
                    h.assertTrue(Math.abs(water - f.water() - buffered - requested) < 1e-5,
                        "Partial admission pays RPM-dependent water while spinning the disconnected input");
                }
                if (op.equals("negative_input")) h.assertTrue(steam.engines.values().stream().allMatch(engine -> engine.omega < 0), "Neutral keeps the existing input rotation direction");
                if (op.equals("release")) {
                    int remaining = f.water();
                    for (int i = 0; i < 10; i++) { control(f, player, List.of()); train.tick(h.getLevel()); }
                    h.assertTrue(f.water() == remaining && train.speed == 0, "Releasing W closes both engine admission and water delivery");
                } else if (op.equals("reload")) {
                    f.reload(); train = f.train;
                    h.assertTrue(TrainPhysics.state(train).gear == 0 && !TrainPhysics.state(train).neutralRev,
                        "Reload retains neutral but does not replay a held drive key");
                    h.assertTrue(TrainPhysics.carriage(train.carriages.getFirst()).engines.values().stream().anyMatch(engine -> Math.abs(engine.rpm()) > 0),
                        "The saved free input rotor retains its actual motion");
                } else if (op.startsWith("shift_")) {
                    int gear = op.equals("shift_reverse") ? TrainMechanics.REVERSE_GEAR : 1;
                    TrainPhysics.shift(train, gear);
                    h.assertTrue(train.speed == 0, "Engaging a spinning input does not assign wheel velocity");
                    for (int i = 0; i < 40; i++) { control(f, player, List.of(0)); train.tick(h.getLevel()); }
                    int direction = (inverted ? -1 : 1) * (gear == TrainMechanics.REVERSE_GEAR ? -1 : 1);
                    h.assertTrue(train.speed * direction > 0, "The clutch engages the selected direction after free revving");
                }
            }
            f.finish();
        });
    }
    private static void gearOrder(GameTestHelper h) {
        int r = TrainMechanics.REVERSE_GEAR;
        int gear = r;
        for (int expected : new int[]{0, 1, 2, 3, 4, 5, 5}) {
            gear = TrainMechanics.stepGear(gear, 1, false);
            h.assertTrue(gear == expected, "Keyboard shift up respects R/N/1/2/3/4/5 and stops at 5");
        }
        for (int expected : new int[]{4, 3, 2, 1, 0, r, r}) {
            gear = TrainMechanics.stepGear(gear, -1, false);
            h.assertTrue(gear == expected, "Keyboard shift down reaches R through neutral and stops at R");
        }
        var player = h.makeMockPlayer(GameType.SURVIVAL); var pos = h.absolutePos(LEVER);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        h.setBlock(LEVER, ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, r));
        for (boolean down : new boolean[]{false, true}) {
            player.setShiftKeyDown(down);
            for (int expected : down ? new int[]{5, 4, 3, 2, 1, 0, r} : new int[]{0, 1, 2, 3, 4, 5, r}) {
                h.getLevel().getBlockState(pos).useWithoutItem(h.getLevel(), player, hit);
                h.assertTrue(h.getBlockState(LEVER).getValue(GearLeverBlock.GEAR) == expected, "Actual physical lever cycles in player gear order");
            }
        }
        h.succeed();
    }
    private static void reload(GameTestHelper h) {
        create(h, TrainMechanics.REVERSE_GEAR, false, f -> {
            h.assertTrue(TrainPhysics.state(f.train).gear == TrainMechanics.REVERSE_GEAR, "Assembly captures R from the actual lever");
            f.reload();
            h.assertTrue(TrainPhysics.state(f.train).gear == TrainMechanics.REVERSE_GEAR, "Native train NBT restores R");
            var state = TrainPhysics.state(f.train); var entity = f.entity();
            var payload = new TrainStatePayload(h.getLevel().dimension().location(), entity.getId(), f.train.id, state.gear,
                true, f.train.speed, f.train.throttle, state.engineRpm, state.failure, List.of());
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                TrainStatePayload.STREAM_CODEC.encode(buffer, payload);
                h.assertTrue(TrainStatePayload.STREAM_CODEC.decode(buffer).gear() == TrainMechanics.REVERSE_GEAR, "HUD network codec accepts R");
            } finally { buffer.release(); }
            h.assertTrue(entity.getContraption().getBlocks().get(LEVER.subtract(TrainTestFixture.WHEEL)).state().getValue(GearLeverBlock.GEAR) == TrainMechanics.REVERSE_GEAR,
                "Loaded carriage restores the physical R lever");
            var player = player(f);
            for (int i = 0; i < 40; i++) { control(f, player, List.of(0)); f.train.tick(h.getLevel()); }
            h.assertTrue(f.train.speed < 0, "Restored R still drives backwards with W");
            f.finish();
        });
    }
    private static void npc(GameTestHelper h, boolean behind) {
        create(h, behind ? 3 : TrainMechanics.REVERSE_GEAR, false, f -> {
            var t = f.train; t.runtime.paused = false; t.navigation.destinationBehindTrain = behind;
            t.navigation.distanceToDestination = 100;
            TrainPhysics.navigate(t); t.tick(h.getLevel());
            h.assertTrue(TrainPhysics.state(t).gear == (behind ? TrainMechanics.REVERSE_GEAR : 1), "Schedule driver selects a gear for its actual navigation direction");
            t.runtime.paused = true;
            TrainPhysics.shift(t, 0); TrainPhysics.navigate(t); t.tick(h.getLevel());
            h.assertTrue(TrainPhysics.state(t).gear == 0, "Player/manual station assistance cannot change selected gear automatically");
            f.finish();
        });
    }
    private static void directionChange(GameTestHelper h) {
        create(h, 1, false, f -> {
            var player = player(f); var train = f.train;
            for (int i = 0; i < 40; i++) { control(f, player, List.of(0)); train.tick(h.getLevel()); }
            double speed = train.speed;
            TrainPhysics.shift(train, TrainMechanics.REVERSE_GEAR);
            h.assertTrue(train.speed == speed && speed > 0, "Selecting R does not assign or flip train velocity");
            control(f, player, List.of(0)); train.tick(h.getLevel());
            h.assertTrue(train.speed > 0 && train.speed < speed, "R applies finite braking while still travelling forwards");
            for (int i = 0; i < 80; i++) { control(f, player, List.of(0)); train.tick(h.getLevel()); }
            h.assertTrue(train.speed < 0, "After braking, W in R propels the train backwards");
            for (int i = 0; i < 80; i++) { control(f, player, List.of(1)); train.tick(h.getLevel()); }
            h.assertTrue(Math.abs(train.speed) < 1e-8, "S also stops a train already moving in reverse");
            f.finish();
        });
    }
}

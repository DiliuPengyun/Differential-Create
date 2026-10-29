package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.ClockworkBearingAccessor;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Actual controllers, target changes during motion and independent hand states. */
final class PositionControlTests {
    private record Sample(double speed, double angle, long tick) {}
    static void clock(GameTestHelper h) {
        var positions = new ArrayList<BlockPos>();
        var daylight = h.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        boolean previousDaylight = daylight.get(); long previousTime = h.getLevel().getDayTime();
        daylight.set(false, h.getLevel().getServer()); h.getLevel().setDayTime(18000);
        for (var facing : Direction.values()) {
            var p = new BlockPos(6 + facing.ordinal() % 3 * 11, 13, 6 + facing.ordinal() / 3 * 14);
            positions.add(p);
            h.setBlock(p, AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            h.assertTrue(!MachiningClock.needsPositionControl(h.getBlockEntity(p)), "unassembled clock does not subdivide unrelated physics");
            var radial = facing.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
            for (int i = 0; i < 6; i++) h.setBlock(p.relative(facing).relative(radial, i), Blocks.SLIME_BLOCK);
            for (int i = 0; i < 3; i++) h.setBlock(p.relative(facing, 2).relative(radial, i), Blocks.HONEY_BLOCK);
            if (facing == Direction.DOWN) {
                var mount = p.relative(facing).relative(radial, 6);
                h.setBlock(mount, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
                h.setBlock(mount.relative(facing), Blocks.SLIME_BLOCK);
                h.setBlock(mount.relative(facing).relative(radial), Blocks.OAK_PLANKS);
            }
            var motor = p.relative(facing.getOpposite());
            h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, facing));
            ((CreativeMotorBlockEntity) h.getBlockEntity(motor)).generatedSpeed.setValue(facing.ordinal() % 2 == 0 ? 64 : -64);
        }
        var samples = new HashMap<ClockworkMotion.Hand, Sample>();
        var rows = new ArrayList<String>(); rows.add("tick,facing,hand,angle_deg,output_rpm,input_rpm,target_error_turns");
        h.runAfterDelay(15, () -> h.getLevel().setDayTime(12500));
        h.runAfterDelay(28, () -> h.getLevel().setDayTime(18000));
        h.runAfterDelay(110, () -> h.getLevel().setDayTime(12500));
        h.runAfterDelay(120, () -> {
            ClockworkBearingBlockEntity be = h.getBlockEntity(positions.getFirst());
            var before = ClockworkMotion.outputs(be).stream().map(o -> new Sample(o.hand().speed, o.hand().angle, 0)).toList();
            h.assertTrue(ContraptionPhysics.drives(be).stream().anyMatch(ContraptionPhysics.Drive::stabilized), "nested clock includes a real stabilized child");
            double mass = ClockworkMotion.of(be).hour.inertia;
            var restored = (ClockworkBearingBlockEntity) reload(h, be);
            var after = ClockworkMotion.outputs(restored);
            h.assertTrue(after.size() == 2, "both clock entities reload");
            for (int i = 0; i < 2; i++) {
                h.assertTrue(after.get(i).hand().speed == before.get(i).speed && after.get(i).hand().angle == before.get(i).angle,
                    "clock reload preserves each hand's independent velocity and integrated coordinate");
            }
            h.assertTrue(ClockworkMotion.of(restored).hour.inertia == mass, "partially reloaded clock retains the saved child inertia");
            h.runAfterDelay(3, () -> h.assertTrue(!ContraptionPhysics.incomplete(restored)
                && Math.abs(ClockworkMotion.of(restored).hour.inertia - mass) < 1e-12, "late child load neither loses nor counts its mass twice"));
        });
        double[] held = new double[6];
        h.runAfterDelay(220, () -> {
            for (int i = 0; i < positions.size(); i++) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i));
                var clock = (ClockworkBearingAccessor) be;
                h.assertTrue(clock.differentialcreate$hour() != null && clock.differentialcreate$minute() != null, "both hands assembled");
                h.assertTrue(MachiningClock.needsPositionControl(be), "assembled clock retains its finite control interval");
                h.assertTrue(Math.abs(ClockworkMotion.of(be).hour.speed) < .02 && Math.abs(ClockworkMotion.of(be).minute.speed) < .02,
                    "both outputs settle before independent target change");
                var load = NetworkLoad.of(be);
                h.assertTrue(load.status() == LoadFactor.Status.AVAILABLE && load.fraction() < 1e-5,
                    "parked hands do not charge their gravity or air drag to the spinning input: " + Direction.values()[i] + " " + load);
                checkClockReadout(be);
                held[i] = clock.differentialcreate$hourAngle();
            }
            h.getLevel().setDayTime(12517); // minute target changes by 6 degrees; hour target is unchanged
        });
        h.onEachTick(() -> {
            for (int i = 0; i < positions.size(); i++) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i));
                if (h.getTick() == 40 || h.getTick() == 130) checkClockReadout(be);
                for (var output : ClockworkMotion.outputs(be)) {
                    var hand = output.hand(); var drive = output.drive();
                    var previous = samples.put(hand, new Sample(hand.speed, hand.angle, h.getTick()));
                    if (previous != null && h.getTick() > 20 && h.getTick() == previous.tick + 1) {
                        var gravity = ContraptionGravity.capture(drive);
                        double force = Math.abs(gravity.linear()) + gravity.harmonics().stream()
                            .mapToDouble(t -> Math.abs(t.frequency()) * Math.hypot(t.cosine(), t.sine())).sum();
                        double drag = ContraptionPhysics.drives(be).stream().filter(d -> d.root() == drive.root()).mapToDouble(ContraptionPhysics.Drive::drag).sum();
                        double maximum = Math.max(Math.abs(previous.speed), Math.abs(hand.speed));
                        double bound = (PositionServo.TORQUE + force + drag * maximum * maximum) * RotationMath.TICK_MINUTES / hand.inertia;
                        h.assertTrue(Math.abs(hand.speed - previous.speed) <= bound + 1e-3, "controller cannot exceed its torque acceleration bound");
                        h.assertTrue(Math.abs(hand.angle - previous.angle) <= (Math.abs(previous.speed) + bound) * RotationMath.TICK_MINUTES + 1e-6,
                            "target changes cannot teleport a hand");
                    }
                    var clock = (ClockworkBearingAccessor) be;
                    double nativeAngle = drive.ratio() == .5 ? clock.differentialcreate$hourAngle() : clock.differentialcreate$minuteAngle();
                    Double pending = ContraptionMotion.syncAngular(drive.root());
                    h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff((float) ((hand.angle * drive.ratio() * 360) % 360),
                        (float) ((nativeAngle + (pending == null ? 0 : pending)) % 360))) < .005,
                        "native pose consumes the same integrated hand coordinate: tick=" + h.getTick() + ", facing=" + Direction.values()[i]
                            + ", ratio=" + drive.ratio() + ", integrated=" + hand.angle * drive.ratio() * 360 + ", native=" + nativeAngle + ", pending=" + pending);
                    rows.add(String.format(Locale.ROOT, "%d,%s,%s,%.9f,%.9f,%.9f,%.9f", h.getTick(), Direction.values()[i],
                        drive.ratio() == .5 ? "hour" : "minute", hand.angle * 360 * drive.ratio(), hand.speed * drive.ratio(), NetworkDynamics.preciseSpeed(be), ClockworkMotion.error(drive)));
                }
            }
        });
        h.runAfterDelay(280, () -> {
            for (int i = 0; i < positions.size(); i++) {
                ClockworkBearingBlockEntity be = h.getBlockEntity(positions.get(i)); var clock = (ClockworkBearingAccessor) be;
                h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff((float) held[i], clock.differentialcreate$hourAngle())) < .01,
                    "moving the minute hand does not move the parked hour hand");
                h.assertTrue(Math.abs(AngleHelper.getShortestAngleDiff(clock.differentialcreate$minuteAngle(), clock.differentialcreate$minuteTarget())) < .01,
                    "minute hand independently reaches its new target");
                be.disassemble();
                checkClockReadout(be);
                h.assertTrue(!MachiningClock.needsPositionControl(be), "disassembled clock releases its control interval");
            }
            save("clock-control.csv", rows);
            h.getLevel().setDayTime(previousTime); daylight.set(previousDaylight, h.getLevel().getServer()); h.succeed();
        });
    }
    private static void checkClockReadout(ClockworkBearingBlockEntity be) {
        var outputs = ClockworkMotion.outputs(be);
        var packet = ClientSpeedSync.captureRotor(be, 0).clock();
        if (packet.size() != outputs.size()) throw new AssertionError("all clock hand readouts must be transmitted");
        double inertia = InertiaRegistry.getInertia(be), speed = NetworkDynamics.preciseSpeed(be);
        double energy = .5 * inertia * speed * speed;
        for (var output : outputs) {
            var state = packet.stream().filter(p -> p.entityId() == output.drive().root().getId()).findFirst().orElseThrow();
            var hand = output.hand();
            if (state.speed() != hand.speed || state.inertia() != hand.inertia)
                throw new AssertionError("clock readout must transmit its independent state, not input or interpolation speed");
            double measured = ContraptionPhysics.drives(be).stream().filter(d -> d.root() == output.drive().root())
                .mapToDouble(ContraptionPhysics.Drive::inertia).sum();
            inertia += measured; energy += .5 * measured * hand.speed * hand.speed;
        }
        assertGoggleValue(be, false, "inertia", inertia);
        assertGoggleValue(be, true, "local_energy", energy);
    }
    static void assertGoggleValue(com.simibubi.create.content.kinetics.base.KineticBlockEntity be,
                                  boolean energy, String key, Double expected) {
        var lines = new ArrayList<net.minecraft.network.chat.Component>();
        KineticGoggleInfo.append(be, lines, energy);
        var line = lines.stream().map(net.minecraft.network.chat.Component::getContents)
            .filter(c -> c instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("gui.differentialcreate.goggles." + key))
            .map(c -> (net.minecraft.network.chat.contents.TranslatableContents) c).findFirst().orElseThrow();
        String text = expected == null ? "—" : String.format("%.2f", expected);
        if (line.getArgs().length != 1 || !text.equals(line.getArgs()[0]))
            throw new AssertionError("incorrect " + key + " readout: expected " + text + ", got " + java.util.Arrays.toString(line.getArgs()));
    }
    static void elevator(GameTestHelper h) {
        var p = new BlockPos(12, 24, 12);
        h.setBlock(p, AllBlocks.ELEVATOR_PULLEY.getDefaultState());
        h.assertTrue(!MachiningClock.needsPositionControl(h.getBlockEntity(p)), "unassembled elevator does not subdivide unrelated physics");
        h.setBlock(p.below(), Blocks.SLIME_BLOCK); h.setBlock(p.below().east(), Blocks.OAK_PLANKS);
        h.setBlock(p.below().west(), AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
        // Native columns persist through unload via their fixed floor contacts.
        for (int floor : new int[]{0, 3, 6}) h.setBlock(p.below().west().north().below(floor),
            AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        var motor = p.west(); h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        ((CreativeMotorBlockEntity) h.getBlockEntity(motor)).generatedSpeed.setValue(64);
        var rows = new ArrayList<String>(); rows.add("tick,offset_m,output_rpm,input_rpm");
        int[] top = new int[1]; ElevatorColumn[] column = new ElevatorColumn[1];
        h.runAfterDelay(12, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p);
            if (!be.running) { try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); } }
            h.assertTrue(be.movedContraption != null, "powered elevator assembled");
            h.assertTrue(MachiningClock.needsPositionControl(be), "assembled elevator retains its finite control interval");
            column[0] = ElevatorColumn.get(h.getLevel(), ((ElevatorContraption) be.movedContraption.getContraption()).getGlobalColumn());
            top[0] = column[0].getTargetedYLevel(); column[0].target(top[0] - 6);
        });
        h.runAfterDelay(24, () -> column[0].target(top[0]));
        h.runAfterDelay(36, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p); double previous = ActorFeed.of(be).speed;
            h.assertTrue(previous < -.1, "legacy migration is exercised while the cabin ascends");
            var restored = (ElevatorPulleyBlockEntity) reload(h, be, true);
            double next = ActorFeed.speed(restored);
            h.assertTrue(next <= 0 && Math.abs(next) <= Math.abs(previous), "legacy target-dependent coordinate migrates without reversing or adding kinetic energy");
            ActorFeed.enabled(restored);
            h.assertTrue(ActorFeed.of(restored).speed == next, "legacy conversion runs only once");
        });
        h.runAfterDelay(100, () -> column[0].target(top[0] - 6));
        h.runAfterDelay(110, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p); double speed = ActorFeed.of(be).speed, angle = ActorFeed.of(be).angle;
            h.assertTrue(Math.abs(speed) > .1, "elevator reload occurs in motion");
            var restored = (ElevatorPulleyBlockEntity) reload(h, be);
            h.assertTrue(ActorFeed.speed(restored) == speed && ActorFeed.of(restored).angle == angle, "elevator reload preserves physical direction and speed");
            var body = (ElevatorContraption) restored.movedContraption.getContraption();
            var restoredColumn = ElevatorColumn.get(h.getLevel(), body.getGlobalColumn());
            h.assertTrue(((com.diliupengyun.differential_create.mixin.ElevatorPulleyAccessor) restored).differentialcreate$targetOffset() == 6,
                "elevator reload retains its floor request: previous=" + column[0].getTargetedYLevel() + ", next="
                    + (restoredColumn == null ? null : restoredColumn.getTargetedYLevel()) + ", available=" + (restoredColumn != null && restoredColumn.isTargetAvailable())
                    + ", range=" + body.minContactY + ".." + body.maxContactY + ", column=" + body.getGlobalColumn() + ", anchor=" + body.anchor);
        });
        h.runAfterDelay(220, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p);
            save("elevator-control.csv", rows);
            h.assertTrue(Math.abs(be.offset - 6) < .001 && Math.abs(ActorFeed.of(be).speed) < .02, "elevator brakes and settles at lower floor: offset=" + be.offset
                + ", speed=" + ActorFeed.of(be).speed + ", target=" + ((com.diliupengyun.differential_create.mixin.ElevatorPulleyAccessor) be).differentialcreate$targetOffset());
            h.setBlock(motor, Blocks.AIR);
        });
        h.runAfterDelay(245, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p);
            h.assertTrue(Math.abs(be.offset - 6) < .001, "finite parking brake holds ordinary cabin after source removal");
            h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            ((CreativeMotorBlockEntity) h.getBlockEntity(motor)).generatedSpeed.setValue(-64);
            column[0].target(top[0] - 3);
        });
        h.onEachTick(() -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p);
            rows.add(String.format(Locale.ROOT, "%d,%.9f,%.9f,%.9f", h.getTick(), be.offset, ActorFeed.of(be).speed, NetworkDynamics.preciseSpeed(be)));
        });
        h.runAfterDelay(355, () -> {
            ElevatorPulleyBlockEntity be = h.getBlockEntity(p);
            h.assertTrue(Math.abs(be.offset - 3) < .001 && Math.abs(ActorFeed.of(be).speed) < .02, "reverse input still drives cabin toward selected floor");
            // Readout-only probe; restore every field before another physics tick.
            // A synchronized reversing contact dissipates no slip power, at any pressure.
            var feed = ActorFeed.of(be);
            double speed = feed.speed, gear = feed.gear, coupling = feed.coupling, twist = feed.twist, brake = feed.brake;
            try {
                feed.gear = -1; feed.speed = -NetworkDynamics.preciseSpeed(be); feed.twist = 0; feed.brake = 0;
                feed.coupling = 0; var open = NetworkLoad.of(be);
                feed.coupling = PositionServo.TORQUE; var engaged = NetworkLoad.of(be);
                h.assertTrue(open.status() == LoadFactor.Status.AVAILABLE && engaged.status() == LoadFactor.Status.AVAILABLE
                    && Double.isFinite(open.fraction()) && Math.abs(open.fraction() - engaged.fraction()) < 1e-9,
                    "synchronized reversing coupling has no phantom slip loss: open=" + open + ", engaged=" + engaged);
            } finally {
                feed.speed = speed; feed.gear = gear; feed.coupling = coupling; feed.twist = twist; feed.brake = brake;
            }
            save("elevator-control.csv", rows); be.disassemble();
            h.assertTrue(!MachiningClock.needsPositionControl(be), "disassembled elevator releases its control interval");
            h.succeed();
        });
    }
    private static com.simibubi.create.content.kinetics.base.KineticBlockEntity reload(GameTestHelper h,
        com.simibubi.create.content.kinetics.base.KineticBlockEntity original) {
        return reload(h, original, false);
    }
    private static com.simibubi.create.content.kinetics.base.KineticBlockEntity reload(GameTestHelper h,
        com.simibubi.create.content.kinetics.base.KineticBlockEntity original, boolean legacy) {
        var registry = h.getLevel().registryAccess();
        var tag = original.saveWithFullMetadata(registry);
        if (legacy) {
            var feed = tag.getCompound("DCActorFeedDrive"); feed.remove("CoordinateVersion");
            feed.putDouble("Speed", Math.abs(feed.getDouble("Speed")));
        }
        var bodies = ContraptionPhysics.drives(original).stream().map(d -> d.entity()).distinct().toList();
        var roots = ContraptionPhysics.drives(original).stream().filter(d -> !d.stabilized()).map(d -> d.root().getUUID()).collect(java.util.stream.Collectors.toSet());
        var entities = bodies.stream().map(root -> {
            var saved = root.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            saved.remove("Passengers");
            saved.putString("id", net.minecraft.world.entity.EntityType.getKey(root.getType()).toString()); return saved;
        }).toList();
        for (int i = bodies.size() - 1; i >= 0; i--) bodies.get(i).remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        NetworkTopology.mark(original);
        var restored = (com.simibubi.create.content.kinetics.base.KineticBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            original.getBlockPos(), original.getBlockState(), tag, registry);
        h.getLevel().setBlockEntity(restored); NetworkTopology.mark(restored); NetworkTopology.flush();
        for (var saved : entities) {
            Runnable load = () -> {
                var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
                h.assertTrue(loaded != null && h.getLevel().addFreshEntity(loaded), "moving entity restores from its saved data");
                if (loaded instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity root) {
                    if (restored instanceof ClockworkBearingBlockEntity clock) clock.attach(root);
                    else ((com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity) restored).attach(root);
                }
            };
            if (roots.contains(saved.getUUID("UUID"))) load.run(); else h.runAfterDelay(2, load);
        }
        return restored;
    }
    private static void save(String name, java.util.List<String> rows) {
        try { var path = java.nio.file.Path.of(System.getProperty("differentialcreate.gravity.report", "build"), name);
            java.nio.file.Files.createDirectories(path.getParent()); java.nio.file.Files.write(path, rows);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}

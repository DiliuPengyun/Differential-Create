package com.diliupengyun.differential_create.tests;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.*;
import com.google.gson.GsonBuilder;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.*;
import com.simibubi.create.content.contraptions.elevator.*;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.*;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real integrated-server fixtures, native entities and ordinary render frames.
 * Only construction, motor commands and player operations are scripted; no body
 * position, velocity or physics time is assigned. Artifacts require visual review. */
public final class StructureClientAudit {
    private record Spec(String name, String kind, Direction facing, int variant, int mode) {}
    private record View(String name, int tick, int entity, Vec3 position, double offset, double speed, BlockPos controller, boolean continuousClock) {}
    private static final List<Spec> cases = new ArrayList<>();
    private static final List<Map<String, Object>> report = new ArrayList<>();
    private static final Path directory = Path.of(System.getProperty("differentialcreate.structureClientReport", "structure-audit"));
    private static final List<String> rows = new ArrayList<>(), failures = new ArrayList<>();
    private static final List<String> clockRows = new ArrayList<>();
    private static final Map<Integer, Vec3[]> childFrames = new HashMap<>();
    private static final Set<String> checkedNested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> checkedClocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> clockReadouts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> clearViews = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean requested, initialized; private static volatile boolean done;
    private static int index = -1, age, frames, captured = -1;
    private static CompletableFuture<?> setup;
    private static volatile View view;
    private static ServerLevel world;
    private static BlockPos base, motor;
    private static Direction motorFacing;
    private static int command;
    private static AbstractContraptionEntity root;
    private static boolean assembled, moved; private static Vec3 start;
    private static long deadline;

    public static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true; deadline = System.nanoTime() + 2_400_000_000_000L;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
                mc.options.guiScale().set(2); mc.options.enableVsync().set(false); mc.options.framerateLimit().set(90);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                var settings = new LevelSettings("Differential Create structure audit", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("structure_audit", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (done) return;
            if (System.nanoTime() > deadline) throw new AssertionError("Structure client audit timed out");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (setup == null) {
                Files.createDirectories(directory); mc.setScreen(null); mc.options.hideGui = true;
                setup = mc.getSingleplayerServer().submit(() -> {
                    world = mc.getSingleplayerServer().overworld(); world.setDayTime(6000);
                    for (var facing : Direction.values()) {
                        for (int shape = 0; shape < 3; shape++) for (int mode = 0; mode < 3; mode++) add("bearing", facing, shape, mode);
                        for (int sticky = 0; sticky < 2; sticky++) for (int mode = 0; mode < 3; mode++) add("piston", facing, sticky, mode);
                        for (int side = 0; side < 2; side++) add("gantry", facing, side, 0);
                        for (int mode = 0; mode < 3; mode++) {
                            add("clock", facing, 0, mode); add("clock", facing, 1, mode);
                        }
                    }
                    for (int shape = 0; shape < 2; shape++) for (int mode : new int[]{0, 2}) {
                        add("pulley", Direction.EAST, shape, mode); add("pulley", Direction.SOUTH, shape, mode);
                    }
                    for (int shape = 0; shape < 2; shape++) {
                        add("elevator", Direction.EAST, shape, 0); add("elevator", Direction.SOUTH, shape, 0);
                    }
                    if (cases.isEmpty()) throw new IllegalArgumentException("No structure cases selected");
                    initialized = true;
                    try { next(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                    return null;
                });
                return;
            }
            if (setup.isCompletedExceptionally()) setup.join();
            var current = view;
            if (current == null || current.tick == captured || current.tick < 5) return;
            boolean boundary = current.tick <= 25 || current.tick >= 174 || Math.abs(current.tick - 45) <= 4
                || Math.abs(current.tick - 95) <= 4 || Math.abs(current.tick - 140) <= 4
                || Math.abs(current.tick - 30) <= 4 || Math.abs(current.tick - 100) <= 4
                || current.continuousClock && Math.abs(current.tick - 155) <= 5;
            if (!boundary && current.tick % 10 != 0) return;
            captured = current.tick;
            var folder = directory.resolve(current.name); Files.createDirectories(folder);
            var replica = mc.level.getEntity(current.entity);
            if (current.name.startsWith("clock_") && current.tick > 35
                && mc.level.getBlockEntity(current.controller) instanceof ClockworkBearingBlockEntity clock) {
                var access = (ClockworkBearingAccessor) clock;
                var hour = access.differentialcreate$hour(); var minute = access.differentialcreate$minute();
                if (hour != null && minute != null) {
                    var first = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.clockRotationOf(clock, hour.getId());
                    var second = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.clockRotationOf(clock, minute.getId());
                    if (first != null && second != null) {
                        if (!checkedClocks.contains(current.name)) {
                            var pending = new ClockworkBearingBlockEntity(clock.getType(), clock.getBlockPos().above(100), clock.getBlockState());
                            pending.setLevel(mc.level);
                            PositionControlTests.assertGoggleValue(pending, false, "inertia", null);
                            PositionControlTests.assertGoggleValue(pending, true, "local_energy", null);
                        }
                        checkedClocks.add(current.name);
                        var inertia = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.clockInertiaOf(clock);
                        var energy = com.diliupengyun.differential_create.client.ClientNetworkSpeeds.clockEnergyOf(clock);
                        double measured = ContraptionPhysics.drives(clock).stream().mapToDouble(ContraptionPhysics.Drive::inertia).sum();
                        if (inertia == null || energy == null || inertia <= 0 || Math.abs(inertia - measured) > 1e-8)
                            throw new AssertionError("Client clock readout omitted its mounted inertia: " + current.name);
                        double inputInertia = InertiaRegistry.getInertia(clock), inputSpeed = NetworkDynamics.preciseSpeed(clock);
                        PositionControlTests.assertGoggleValue(clock, false, "inertia", inputInertia + inertia);
                        PositionControlTests.assertGoggleValue(clock, true, "local_energy", .5 * inputInertia * inputSpeed * inputSpeed + energy);
                        boolean parkedWindow = current.continuousClock ? current.tick >= 60 && current.tick < 95 : current.tick >= 140;
                        clockReadouts.merge(current.name, energy > 1e-6 ? 1 : parkedWindow ? 2 : 0, (a, b) -> a | b);
                        var clockRows = folder.resolve("clock-readout.csv");
                        if (!Files.exists(clockRows)) Files.writeString(clockRows, "tick,hand_inertia,hand_energy,input_rpm,local_energy\n");
                        Files.writeString(clockRows, current.tick + "," + inertia + "," + energy + "," + inputSpeed + ","
                            + (.5 * inputInertia * inputSpeed * inputSpeed + energy) + "\n", StandardOpenOption.APPEND);
                        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
                            float expected = com.diliupengyun.differential_create.client.BearingAnimation.angleAt(first, partial);
                            float minuteExpected = com.diliupengyun.differential_create.client.BearingAnimation.angleAt(second, partial);
                            if (Math.abs(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(hour.getAngle(partial), expected)) > 1e-4
                                || Math.abs(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(minute.getAngle(partial), minuteExpected)) > 1e-4
                                || Math.abs(net.createmod.catnip.math.AngleHelper.getShortestAngleDiff(clock.getInterpolatedAngle(partial - 1), expected)) > 1e-4)
                                throw new AssertionError("Clock hand or top plate differs from accepted subframe: " + current.name + " tick=" + current.tick);
                        }
                    }
                }
            }
            if (current.name.startsWith("bearing_") && current.name.matches("bearing_.*_2_[012]")
                && current.tick > 30 && replica instanceof AbstractContraptionEntity parent) {
                for (var passenger : parent.getPassengers()) if (passenger instanceof AbstractContraptionEntity child) {
                    checkedNested.add(current.name);
                    var axes = new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
                    var reference = childFrames.computeIfAbsent(child.getId(), key -> Arrays.stream(axes)
                        .map(axis -> child.applyRotation(axis, 1)).toArray(Vec3[]::new));
                    for (float partial : new float[]{0, .25f, .5f, .75f, 1}) for (int axis = 0; axis < 3; axis++)
                        if (child.applyRotation(axes[axis], partial).distanceTo(reference[axis]) > 1e-4)
                            throw new AssertionError("Stabilized child changes world orientation: " + current.name + " tick=" + current.tick);
                }
            }
            if (current.name.startsWith("elevator_") && current.name.endsWith("_1_0") && current.tick > 35) {
                var primary = (ElevatorPulleyBlockEntity) mc.level.getBlockEntity(current.controller);
                var passive = (ElevatorPulleyBlockEntity) mc.level.getBlockEntity(current.controller.east(4));
                if (primary == null || passive == null || passive.getAttachedContraption() == null)
                    throw new AssertionError("The client secondary pulley has no shared cabin");
                for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
                    float main = com.simibubi.create.content.contraptions.pulley.PulleyRenderer.getBlockEntityOffset(partial, primary);
                    float secondary = com.simibubi.create.content.contraptions.pulley.PulleyRenderer.getBlockEntityOffset(partial, passive);
                    if (Math.abs(main - secondary) > 1e-4)
                        throw new AssertionError("The rendered secondary rope loses cabin contact: " + secondary + " vs " + main);
                }
            }
            var clientRows = folder.resolve("client.csv");
            if (!Files.exists(clientRows)) Files.writeString(clientRows, "tick,entity,exists,x_m,y_m,z_m,server_error_m,blocks,ready,alive,min_x,min_y,min_z,max_x,max_y,max_z\n");
            Vec3 clientPos = replica == null ? Vec3.ZERO : replica.position();
            int blocks = replica instanceof AbstractContraptionEntity c && c.getContraption() != null ? c.getContraption().getBlocks().size() : 0;
            Files.writeString(clientRows, current.tick + "," + current.entity + "," + (replica != null) + "," + clientPos.x + "," + clientPos.y + "," + clientPos.z
                + "," + (replica == null ? -1 : clientPos.distanceTo(current.position)) + "," + blocks
                + "," + (replica instanceof AbstractContraptionEntity c && c.isReadyForRender())
                + "," + (replica instanceof AbstractContraptionEntity c && c.isAliveOrStale())
                + (replica == null ? ",0,0,0,0,0,0" : "," + replica.getBoundingBox().minX + "," + replica.getBoundingBox().minY + "," + replica.getBoundingBox().minZ
                    + "," + replica.getBoundingBox().maxX + "," + replica.getBoundingBox().maxY + "," + replica.getBoundingBox().maxZ)
                + "\n", StandardOpenOption.APPEND);
            if (mc.screen == null) {
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(folder.resolve(String.format(Locale.ROOT, "%03d.png", current.tick)));
                }
                clearViews.merge(current.name, current.tick < 95 ? 1 : current.tick >= 140 ? 2 : 0, (a, b) -> a | b);
            }
        } catch (Throwable failure) {
            DifferentialCreate.LOGGER.error("DC_STRUCTURE_CLIENT_FAIL", failure); done = true; mc.stop();
        }
    }
    private static void add(String kind, Direction facing, int variant, int mode) {
        String name = kind + "_" + facing.getName() + "_" + variant + "_" + mode;
        if (Arrays.stream(System.getProperty("differentialcreate.structureClientSelect", ".*").split(",")).anyMatch(name::matches))
            cases.add(new Spec(name, kind, facing, variant, mode));
    }
    public static void tick(ServerTickEvent.Post event) {
        if (!initialized || done || world == null || index < 0) return;
        try {
            var spec = cases.get(index); age++;
            boolean continuousClock = spec.kind.equals("clock") && spec.variant == 1;
            // Let real chunks, meshes and entity tracking arrive after teleport.
            // Starting the motor during the first loading frame hides assembly.
            if (age < 0) return;
            if (age == 0) power(command);
            if (spec.kind.equals("clock") && !continuousClock && age == 30) world.setDayTime(12500);
            if (spec.kind.equals("clock") && !continuousClock && age == 100) world.setDayTime(18000);
            if (continuousClock && age == 95) world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, world.getServer());
            if (age == 20) {
                var be = world.getBlockEntity(base);
                if (be instanceof MechanicalBearingBlockEntity bearing && !bearing.isRunning()) bearing.assemble();
                else if (be instanceof ClockworkBearingBlockEntity clock && !clock.isRunning()) clock.assemble();
                else if (be instanceof ElevatorPulleyBlockEntity elevator) elevator.clicked();
                if (be instanceof ClockworkBearingAccessor clock
                    && (clock.differentialcreate$hour() == null || clock.differentialcreate$minute() == null))
                    throw new AssertionError("Clock fixture must assemble two independently attached hands");
            }
            if (spec.kind.equals("elevator") && (age == 30 || age == 100)) {
                var elevator = (ElevatorPulleyBlockEntity) world.getBlockEntity(base);
                if (elevator.movedContraption == null) throw new AssertionError("Elevator failed to assemble");
                var body = (ElevatorContraption) elevator.movedContraption.getContraption();
                var column = ElevatorColumn.get(world, body.getGlobalColumn());
                column.target(base.getY() - 1 - (age == 30 ? 4 : 0));
                // Match a real floor-contact request, including save persistence.
                column.markDirty();
            }
            if (root == null || root.isRemoved()) root = findRoot(spec);
            if (spec.kind.equals("elevator") && spec.variant == 1 && age > 25) {
                var follower = world.getBlockEntity(base.east(4));
                if (!(follower instanceof ElevatorPulleyBlockEntity passive) || !base.equals(passive.getMirrorParent()))
                    throw new AssertionError("Secondary pulley must remain fixed and mirror the main pulley");
            }
            if (root != null && !root.isRemoved()) {
                assembled = true;
                if (start == null) start = root.position();
                moved |= root.position().distanceTo(start) > .05 || root instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity c && Math.abs(c.getAngle(1)) > 5;
            }
            if (age == 45 && !continuousClock) world.setBlockAndUpdate(motor, Blocks.AIR.defaultBlockState());
            if (age == 95 && !continuousClock) power(-command);
            if (age == 140 && spec.kind.equals("gantry")) {
                Direction powerSide = spec.facing.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
                for (int n = -10; n <= 10; n++) {
                    var pos = base.relative(spec.facing, n);
                    put(pos.relative(powerSide), Blocks.REDSTONE_BLOCK.defaultBlockState());
                }
            }
            var owner = root instanceof GantryContraptionEntity gantry ? ContraptionPhysics.gantryShaft(gantry)
                : world.getBlockEntity(base) instanceof KineticBlockEntity be ? be : null;
            double offset = owner instanceof LinearActuatorBlockEntity a ? a.offset : 0;
            double speed = owner == null ? 0 : NetworkDynamics.preciseSpeed(owner);
            if (spec.kind.equals("elevator") && (age == 90 || age == 178)
                && (Math.abs(offset - (age == 90 ? 4 : 0)) > 1e-4 || Math.abs(ActorFeed.speed(owner)) > 1e-4))
                throw new AssertionError("Elevator must reach and hold its floor: offset=" + offset + ", output=" + ActorFeed.speed(owner));
            Vec3 position = root == null ? Vec3.ZERO : root.position();
            if (!Double.isFinite(position.lengthSqr() + offset + speed)) throw new AssertionError("Nonfinite native structure state");
            if (owner instanceof LinearActuatorBlockEntity a && a.running && (offset < -1e-5
                || offset > ((LinearActuatorAccessor) a).differentialcreate$extensionRange() + 1e-5))
                throw new AssertionError("Body exceeded native extension range: " + offset);
            rows.add(age + "," + (root == null ? -1 : root.getId()) + "," + position.x + "," + position.y + "," + position.z
                + "," + offset + "," + speed + "," + (owner == null ? 0 : ActorFeed.speed(owner)) + "," + (root == null || root.isRemoved())
                + "," + (root instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity c ? c.getAngle(1) : 0)
                + "," + ContraptionMotion.angular(root));
            if (owner instanceof ClockworkBearingBlockEntity clock) {
                var hands = ClockworkMotion.of(clock); var access = (ClockworkBearingAccessor) clock;
                if (access.differentialcreate$minute() != null) moved |= Math.abs(access.differentialcreate$minute().getAngle(1)) > 5;
                clockRows.add(age + "," + world.getDayTime() + "," + hands.hour.angle * 180 + "," + hands.minute.angle * 360
                    + "," + hands.hour.speed * .5 + "," + hands.minute.speed);
            }
            view = new View(spec.name, age, root == null ? -1 : root.getId(), position, offset, speed, base, continuousClock);
            if (age >= 180) {
                if (!assembled || !moved) failures.add(spec.name + ": assembled=" + assembled + ", moved=" + moved);
                if (spec.kind.equals("bearing") && spec.variant == 2 && !checkedNested.contains(spec.name))
                    failures.add(spec.name + ": no rendered stabilized child was checked");
                if (spec.kind.equals("clock") && !checkedClocks.contains(spec.name)) failures.add(spec.name + ": both rendered hand frames were not checked");
                if (spec.kind.equals("clock") && clockReadouts.getOrDefault(spec.name, 0) != 3)
                    failures.add(spec.name + ": moving and parked independent hand energy readouts were not both checked");
                if (clearViews.getOrDefault(spec.name, 0) != 3) failures.add(spec.name + ": unobstructed early and late screenshots are missing");
                finish(); next();
            }
        } catch (Throwable failure) {
            DifferentialCreate.LOGGER.error("DC_STRUCTURE_CASE_FAIL: " + cases.get(index).name, failure);
            failures.add(cases.get(index).name + ": " + failure);
            try { finish(); next(); } catch (Exception fatal) { done = true; DifferentialCreate.LOGGER.error("DC_STRUCTURE_CLIENT_FAIL", fatal); Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop()); }
        }
    }
    private static AbstractContraptionEntity findRoot(Spec spec) {
        var be = world.getBlockEntity(base);
        if (be instanceof MechanicalBearingBlockEntity b) return b.getMovedContraption();
        if (be instanceof ClockworkBearingBlockEntity c) return ((ClockworkBearingAccessor) c).differentialcreate$hour();
        if (be instanceof LinearActuatorBlockEntity a) return a.movedContraption;
        return java.util.stream.StreamSupport.stream(world.getAllEntities().spliterator(), false)
            .filter(e -> e instanceof GantryContraptionEntity && !e.isRemoved() && e.position().distanceTo(Vec3.atCenterOf(base)) < 15)
            .map(e -> (AbstractContraptionEntity) e).findFirst().orElse(null);
    }
    private static void next() throws Exception {
        if (++index == cases.size()) {
            done = true; Files.writeString(directory.resolve("failures.json"), new GsonBuilder().setPrettyPrinting().create().toJson(failures));
            DifferentialCreate.LOGGER.info(failures.isEmpty() ? "DC_STRUCTURE_CLIENT_PASS: {} real-world constructions, screenshots require review"
                : "DC_STRUCTURE_CLIENT_FAIL: {} cases; see failures.json", cases.size());
            if (!Boolean.getBoolean("differentialcreate.structureClientHold")) Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            return;
        }
        var s = cases.get(index); age = -80; root = null; start = null; assembled = moved = false; rows.clear(); view = null;
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, world.getServer());
        world.setDayTime(s.kind.equals("clock") && s.variant == 1 ? 17940 : 6000);
        rows.add("tick,entity,x_m,y_m,z_m,offset_m,input_rpm,output_rpm,removed,angle_deg,pending_deg");
        clockRows.clear(); clockRows.add("tick,day_time,first_angle,second_angle,first_rpm,second_rpm");
        int grid = index + Integer.getInteger("differentialcreate.structureClientGridStart", 0);
        base = new BlockPos((grid % 8) * 48, -40, (grid / 8) * 48);
        var axis = s.facing.getAxis();
        var radial = axis == Direction.Axis.Y ? Direction.EAST : Direction.UP;
        if (s.kind.equals("bearing") || s.kind.equals("clock")) {
            put(base, (s.kind.equals("clock") ? AllBlocks.CLOCKWORK_BEARING : AllBlocks.MECHANICAL_BEARING)
                .getDefaultState().setValue(BlockStateProperties.FACING, s.facing));
            if (world.getBlockEntity(base) instanceof MechanicalBearingBlockEntity bearing)
                ((MechanicalBearingAccessor) bearing).differentialcreate$getMovementMode().setValue(s.mode);
            var anchor = base.relative(s.facing);
            for (int n = s.variant == 1 ? -2 : 0; n <= 2; n++) put(anchor.relative(radial, n), Blocks.SLIME_BLOCK.defaultBlockState());
            if (s.variant == 1) {
                // Keep coloured tips beyond the honey arm: a tip beside honey
                // would glue both hands together through the concrete.
                int tip = s.kind.equals("clock") ? 3 : 2;
                put(anchor.relative(radial, tip), Blocks.RED_CONCRETE.defaultBlockState());
                put(anchor.relative(radial, -tip), Blocks.YELLOW_CONCRETE.defaultBlockState());
            } else put(anchor.relative(radial, 3), Blocks.RED_CONCRETE.defaultBlockState());
            if (s.kind.equals("clock")) {
                ((ClockworkBearingAccessor) world.getBlockEntity(base)).differentialcreate$operationMode().setValue(s.mode);
                for (int n = 0; n < 3; n++) put(base.relative(s.facing, 2).relative(radial, n), Blocks.HONEY_BLOCK.defaultBlockState());
                put(base.relative(s.facing, 2).relative(radial, 3), Blocks.BLUE_CONCRETE.defaultBlockState());
            }
            if (s.variant == 2) {
                put(anchor.relative(radial, 3), AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, s.facing));
                for (int n = 0; n < 3; n++) put(anchor.relative(radial, 3).relative(s.facing).relative(radial, n), Blocks.SLIME_BLOCK.defaultBlockState());
                put(anchor.relative(radial, 6).relative(s.facing), Blocks.YELLOW_CONCRETE.defaultBlockState());
            }
            motor = base.relative(s.facing.getOpposite()); motorFacing = s.facing; command = 24;
        } else if (s.kind.equals("gantry")) {
            for (int n = -10; n <= 10; n++) put(base.relative(s.facing, n), AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(GantryShaftBlock.FACING, s.facing));
            Direction side = axis == Direction.Axis.X ? (s.variant == 0 ? Direction.SOUTH : Direction.UP)
                : axis == Direction.Axis.Y ? (s.variant == 0 ? Direction.EAST : Direction.SOUTH) : (s.variant == 0 ? Direction.EAST : Direction.UP);
            var carriage = base.relative(side);
            put(carriage, AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, side));
            for (int n = 2; n <= 4; n++) put(base.relative(side, n), n == 4 ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.SLIME_BLOCK.defaultBlockState());
            motor = base.relative(s.facing, -11); motorFacing = s.facing; command = 32;
        } else {
            var state = s.kind.equals("piston") ? (s.variant == 0 ? AllBlocks.STICKY_MECHANICAL_PISTON : AllBlocks.MECHANICAL_PISTON).getDefaultState().setValue(BlockStateProperties.FACING, s.facing)
                : s.kind.equals("elevator") ? AllBlocks.ELEVATOR_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, s.facing.getCounterClockWise())
                : AllBlocks.ROPE_PULLEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
            put(base, state);
            Direction travel = s.kind.equals("piston") ? s.facing : Direction.DOWN;
            if (s.kind.equals("piston")) for (int n = 1; n <= 8; n++) put(base.relative(travel.getOpposite(), n),
                AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(BlockStateProperties.FACING, travel));
            var anchor = base.relative(travel);
            Direction width = travel.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
            for (int n = 0; n < (s.variant == 1 ? 4 : 2); n++) put(anchor.relative(width, n), Blocks.SLIME_BLOCK.defaultBlockState());
            put(anchor.relative(width, s.variant == 1 ? 4 : 2), Blocks.RED_CONCRETE.defaultBlockState());
            if (s.kind.equals("elevator")) {
                put(anchor.west(), AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.NORTH));
                for (int floor : new int[]{0, 4}) put(anchor.west().north().below(floor),
                    AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
                // The red end block is pulled by adjacent slime, but does not
                // glue the secondary pulley itself into the moving cabin.
                if (s.variant == 1) put(base.east(4), state);
            }
            for (int n = 0; n <= (s.variant == 1 ? 4 : 2); n++) put(anchor.relative(width, n).relative(travel, 5), Blocks.STONE.defaultBlockState());
            var be = (LinearActuatorBlockEntity) world.getBlockEntity(base);
            var movementMode = ((LinearActuatorAccessor) be).differentialcreate$movementMode();
            if (movementMode != null) movementMode.setValue(s.mode);
            var input = ((IRotate) state.getBlock()).getRotationAxis(state);
            motorFacing = Direction.fromAxisAndDirection(input, Direction.AxisDirection.POSITIVE); motor = base.relative(motorFacing.getOpposite());
            command = !s.kind.equals("piston") ? 64 : 64 * (axis == Direction.Axis.Z ? 1 : -1) * s.facing.getAxisDirection().getStep();
        }
        var player = world.getServer().getPlayerList().getPlayers().getFirst();
        player.getAbilities().flying = true; player.onUpdateAbilities();
        var target = Vec3.atCenterOf(base).add(0, -2, 0);
        boolean continuousClock = s.kind.equals("clock") && s.variant == 1;
        if (continuousClock) for (var direction : Direction.values())
            put(base.relative(direction, 5), Blocks.LIGHT.defaultBlockState());
        var camera = continuousClock ? target.add(7, 3, 8) : target.add(12, 6, 14);
        var delta = target.subtract(camera.add(0, player.getEyeHeight(), 0));
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
        DifferentialCreate.LOGGER.info("DC_STRUCTURE_CASE: {}/{} {} at {}", index + 1, cases.size(), s.name, base);
    }
    private static void power(int rpm) {
        put(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, motorFacing));
        ((CreativeMotorBlockEntity) world.getBlockEntity(motor)).generatedSpeed.setValue(rpm);
    }
    private static void put(BlockPos pos, BlockState state) { world.setBlockAndUpdate(pos, state); }
    private static void finish() throws Exception {
        var s = cases.get(index); var folder = directory.resolve(s.name); Files.createDirectories(folder);
        Files.write(folder.resolve("server.csv"), rows);
        if (s.kind.equals("clock")) Files.write(folder.resolve("clock-server.csv"), clockRows);
        report.add(Map.of("name", s.name, "controller", base.toShortString(), "assembled", assembled, "moved", moved));
        Files.writeString(directory.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        world.setBlockAndUpdate(motor, Blocks.AIR.defaultBlockState());
    }
}

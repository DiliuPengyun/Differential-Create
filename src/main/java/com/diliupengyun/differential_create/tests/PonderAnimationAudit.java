package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.client.ClientKineticAngles;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.google.gson.Gson;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Records intermediate poses and checks continuity across tick boundaries, including split outputs. */
final class PonderAnimationAudit {
    private record Phase(int tick, Double input, Double output, Double mounted) {}
    private record RotorFrame(String position, String block, double rpm, Double input, Double output, Double mounted) {}
    private record SectionFrame(int id, double fade, Vec3 offset, Vec3 rotation, List<String> blocks) {}
    private record Frame(int tick, List<RotorFrame> rotors, List<SectionFrame> sections, Map<String, String> changes,
        Map<String, List<Double>> mechanisms) {}
    private record WheelPose(double x, double angle, double speed) {}

    private static final Field SELECTION = field(WorldSectionElementImpl.class, "section");
    private static final Field FADE = field(AnimatedSceneElementBase.class, "fade");
    private final Map<KineticBlockEntity, Phase> phases = new IdentityHashMap<>();
    private final Map<WorldSectionElement, Integer> sectionIds = new IdentityHashMap<>();
    private final Map<BlockPos, String> blocks = new LinkedHashMap<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<BlockPos, WheelPose> wheels = new LinkedHashMap<>();
    private final Map<String, Integer> steamContacts = new LinkedHashMap<>();
    private final PonderFanAudit fans = new PonderFanAudit();
    private final PonderGearshiftAudit gearshifts = new PonderGearshiftAudit();

    void sample(PonderScene scene) {
        fans.sample(scene);
        gearshifts.sample(scene);
        int tick = scene.getCurrentTime();
        var physics = PonderKinetics.of(scene.getWorld());
        var rotors = new ArrayList<RotorFrame>();
        for (var be : scene.getWorld().getBlockEntities()) {
            if (!(be instanceof KineticBlockEntity kinetic)) continue;
            Double input = physics.angle(kinetic, 1), mounted = physics.outputAngle(kinetic, 1);
            Double output = output(physics, kinetic, 1);
            var previous = phases.put(kinetic, new Phase(tick, input, output, mounted));
            if (previous != null && previous.tick() == tick - 1) {
                continuous(scene, kinetic, "input", previous.input(), physics.angle(kinetic, 0));
                continuous(scene, kinetic, "output", previous.output(), output(physics, kinetic, 0));
                continuous(scene, kinetic, "mounted", previous.mounted(), physics.outputAngle(kinetic, 0));
            }
            rotors.add(new RotorFrame(be.getBlockPos().toShortString(), be.getBlockState().toString(),
                kinetic.getSpeed(), input, output, mounted));
        }
        var sections = new ArrayList<SectionFrame>();
        var changes = new LinkedHashMap<String, String>();
        var mechanisms = new LinkedHashMap<String, List<Double>>();
        scene.forEachVisible(WorldSectionElement.class, section -> {
            try {
                var selection = (Selection) SELECTION.get(section);
                if (selection == null) return;
                int id = sectionIds.computeIfAbsent(section, ignored -> sectionIds.size());
                var members = new ArrayList<String>();
                for (var pos : selection) {
                    var state = scene.getWorld().getBlockState(pos);
                    String value = state.toString();
                    String old = blocks.put(pos.immutable(), value);
                    if (scene.getId().getPath().equals("steam_engine") && state.isAir() && old != null
                        && (old.contains("create:powered_shaft") || old.contains("create:cogwheel")) && failures.size() < 12)
                        failures.add("Steam assembly loses a shaft or gear during playback at tick " + tick + ": " + pos);
                    if (!value.equals(old)) changes.put(pos.toShortString(), value);
                    if (!state.isAir()) members.add(pos.toShortString());
                    if (scene.getId().getNamespace().equals("differentialcreate") && AllBlocks.GANTRY_CARRIAGE.has(state)) {
                        var support = BlockPos.containing(Vec3.atCenterOf(pos).add(section.getAnimatedOffset())).below();
                        if (!AllBlocks.GANTRY_SHAFT.has(scene.getWorld().getBlockState(support)) && failures.size() < 12)
                            failures.add("Gantry carriage leaves its shaft at tick " + tick + ": " + support);
                    }
                    if (scene.getWorld().getBlockEntity(pos)
                        instanceof com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity bogey) {
                        double x = section.getAnimatedOffset().x, angle = bogey.getVirtualAngle(1);
                        var oldWheel = wheels.get(pos);
                        double speed = oldWheel == null ? 0 : x - oldWheel.x();
                        mechanisms.put("bogey " + pos.toShortString(), List.of(x, angle, speed));
                        if (oldWheel != null && scene.getId().getPath().equals("train_driving")) {
                            var block = (com.simibubi.create.content.trains.bogey.AbstractBogeyBlock<?>) state.getBlock();
                            double expected = speed * 360 / (Math.PI * 2 * block.getWheelRadius());
                            if (Math.abs(Math.IEEEremainder(angle - oldWheel.angle() - expected, 360)) > .01 && failures.size() < 12)
                                failures.add("Train wheels slip against their displayed travel at tick " + tick);
                            if (Math.abs(speed - oldWheel.speed()) > .003 && failures.size() < 12)
                                failures.add("Train movement jumps in speed at tick " + tick + ": " + oldWheel.speed() + " -> " + speed);
                        }
                        wheels.put(pos.immutable(), new WheelPose(x, angle, speed));
                    }
                }
                sections.add(new SectionFrame(id, ((LerpedFloat) FADE.get(section)).getValue(1),
                    section.getAnimatedOffset(), section.getAnimatedRotation(), members));
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        });
        scene.forEachVisible(net.createmod.ponder.api.element.MinecartElement.class, cart -> {
            var offset = cart.getPositionOffset();
            mechanisms.put("cart " + mechanisms.size(), List.of(offset.x, offset.y, offset.z));
            if (scene.getId().getPath().equals("minecart_dynamics"))
                for (var section : sections) if (section.blocks().contains("3, 2, 2")
                    && offset.distanceTo(section.offset()) > 1e-6 && failures.size() < 12)
                    failures.add("Minecart and cargo separate at tick " + tick);
        });
        steamAssembly(scene, sections, mechanisms);
        for (var be : scene.getWorld().getBlockEntities()) {
            String pos = be.getBlockPos().toShortString();
            if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank && tank.isController())
                mechanisms.put("boiler " + pos, List.of((double) tank.getWidth(), (double) tank.getHeight(),
                    Boolean.TRUE.equals(tank.getExtraData()) ? 1d : 0d, tank.boiler.isActive() ? 1d : 0d,
                    (double) tank.boiler.activeHeat, com.diliupengyun.differential_create.kinetics.BoilerAdmission.fraction(tank.boiler),
                    (double) tank.boiler.gauge.getValue(1), (double) tank.getTankInventory().getFluidAmount()));
            if (be instanceof com.simibubi.create.content.logistics.depot.EjectorBlockEntity ejector) {
                var target = ejector.getTargetPosition();
                mechanisms.put("ejector " + pos, List.of((double) target.getX(), (double) target.getY(), (double) target.getZ(),
                    (double) ejector.getState().ordinal()));
            }
        }
        scene.forEachVisible(com.diliupengyun.differential_create.client.ponder.PonderHoseElement.class, hose -> {
            var connection = hose.connection(scene.getWorld(), 1);
            if (connection != null) mechanisms.put("hose " + hose.enginePos().toShortString(), List.of(
                connection.engine().point().x, connection.engine().point().y, connection.engine().point().z,
                connection.tank().point().x, connection.tank().point().y, connection.tank().point().z));
        });
        if (Boolean.getBoolean("differentialcreate.ponderAudit"))
            frames.add(new Frame(tick, rotors, sections, changes, mechanisms));
    }

    /** Inspect the actual cylinder renderer's input and the shafts that meet on screen. */
    private void steamAssembly(PonderScene scene, List<SectionFrame> sections, Map<String, List<Double>> mechanisms) {
        if (!scene.getId().getPath().equals("steam_engine") && !scene.getId().getPath().equals("steam_power")) return;
        var displayed = new LinkedHashMap<KineticBlockEntity, Vec3>();
        for (var section : sections) {
            if (section.fade() < .999 || !section.rotation().equals(Vec3.ZERO)) continue;
            for (var coordinates : section.blocks()) {
                String[] parts = coordinates.split(", ");
                var pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                var be = scene.getWorld().getBlockEntity(pos);
                if (be instanceof KineticBlockEntity kinetic)
                    displayed.put(kinetic, Vec3.atLowerCornerOf(pos).add(section.offset()));
                if (be instanceof com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity engine) {
                    var shaft = engine.getShaft();
                    Float cylinder = engine.getTargetAngle();
                    if (shaft == null || cylinder == null) continue;
                    double shaftAngle = ClientKineticAngles.shaftAngleOf(shaft);
                    var facing = com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.getFacing(engine.getBlockState());
                    var axis = com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationAxisOf(shaft);
                    double expected = shaftAngle;
                    if (axis.isHorizontal() && (facing.getAxis() == net.minecraft.core.Direction.Axis.X
                        ^ facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE)) expected *= -1;
                    if (axis == net.minecraft.core.Direction.Axis.X && facing == net.minecraft.core.Direction.DOWN) expected *= -1;
                    if (Math.abs(Math.IEEEremainder(cylinder - expected, Math.PI * 2)) > 1e-5 && failures.size() < 12)
                        failures.add("Steam linkage and its rendered crank disagree at tick " + scene.getCurrentTime() + ": " + pos);
                    mechanisms.put("steam " + pos.toShortString(), List.of((double) cylinder, shaftAngle, (double) shaft.getSpeed()));
                }
            }
        }
        if (!scene.getId().getPath().equals("steam_engine")) return;
        var current = new java.util.HashSet<String>();
        for (var left : displayed.entrySet()) for (var right : displayed.entrySet()) {
            var a = left.getKey(); var b = right.getKey();
            if (a.getBlockPos().compareTo(b.getBlockPos()) >= 0) continue;
            var axis = com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationAxisOf(a);
            if (axis != com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getRotationAxisOf(b)) continue;
            Vec3 delta = right.getValue().subtract(left.getValue());
            double along = axis.choose(delta.x, delta.y, delta.z);
            if (Math.abs(delta.lengthSqr() - 1) > 1e-6) continue;
            boolean mesh = AllBlocks.COGWHEEL.has(a.getBlockState()) && AllBlocks.COGWHEEL.has(b.getBlockState())
                && Math.abs(along) < 1e-6;
            if (!mesh && Math.abs(Math.abs(along) - 1) > 1e-6) continue;
            String key = a.getBlockPos() + " / " + b.getBlockPos(); current.add(key);
            int age = steamContacts.merge(key, 1, Integer::sum);
            // Finite elastic shafts have small instantaneous speed differences while
            // accelerating. The much tighter phase bound also detects persistent drift.
            if (age >= 30 && Math.abs(a.getSpeed() - (mesh ? -b.getSpeed() : b.getSpeed())) > 2 && failures.size() < 12)
                failures.add("Displayed steam connection runs at incompatible speeds at tick " + scene.getCurrentTime() + ": " + key);
            var physics = PonderKinetics.of(scene.getWorld());
            double phase = mesh ? physics.angle(a, 1) + physics.angle(b, 1)
                : Math.toDegrees(ClientKineticAngles.shaftAngleOf(a) - ClientKineticAngles.shaftAngleOf(b));
            mechanisms.put("steam contact " + key, List.of(phase, (double) a.getSpeed(), (double) b.getSpeed()));
            if (Math.abs(Math.IEEEremainder(phase, 90)) > .2 && failures.size() < 12)
                failures.add("Displayed steam shaft or gear phases do not align at tick " + scene.getCurrentTime() + ": " + key + " phase " + phase);
        }
        steamContacts.keySet().retainAll(current);
        if (scene.getCurrentTime() >= scene.getTotalTime() - 5) {
            long shafts = displayed.keySet().stream().filter(be -> AllBlocks.POWERED_SHAFT.has(be.getBlockState())).count();
            long gears = displayed.keySet().stream().filter(be -> be.getBlockPos().getX() == 1
                && AllBlocks.COGWHEEL.has(be.getBlockState())).count();
            if ((shafts != 8 || gears != 5) && failures.size() < 12)
                failures.add("Native steam assembly lost a part: " + shafts + " powered shafts and " + gears + " gears");
            for (var be : displayed.keySet()) if (be instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity
                && Math.abs(be.getSpeed()) < 450 && failures.size() < 12)
                failures.add("Unloaded native steam assembly never reaches its governor range: " + be.getBlockPos() + " at " + be.getSpeed());
        }
    }

    private void continuous(PonderScene scene, KineticBlockEntity be, String port, Double before, Double after) {
        if (before == null || after == null) return;
        double jump = Math.IEEEremainder(after - before, 360);
        if ((!Double.isFinite(jump) || Math.abs(jump) > .001) && failures.size() < 12)
            failures.add(scene.getId() + " tick " + scene.getCurrentTime() + " " + be.getBlockPos().toShortString()
                + " " + port + " jumps by " + jump + " degrees between rendered ticks");
    }

    private static Double output(PonderKinetics physics, KineticBlockEntity be, float partial) {
        if (be instanceof ClutchBlockEntity clutch) return physics.clutchOutputAngle(clutch, partial);
        return be instanceof ISequencerDynamics ? physics.sequencerOutputAngle(be, partial) : null;
    }

    void save(PonderScene scene, String component) throws Exception {
        Path directory = Path.of(System.getProperty("differentialcreate.ponderPreviewPath", "ponder-previews"))
            .resolve("audit").resolve("timelines");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(scene.getId().getNamespace() + "-" + scene.getId().getPath()
            + "-" + component + ".json"), new Gson().toJson(Map.of("frames", frames, "failures", failures)));
    }

    void verify(PonderScene scene) {
        fans.verify(scene);
        gearshifts.verify(scene);
        if (!failures.isEmpty()) throw new AssertionError("Discontinuous animation in " + scene.getId() + ": " + failures);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}

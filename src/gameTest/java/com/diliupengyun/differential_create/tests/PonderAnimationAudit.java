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
import com.diliupengyun.differential_create.client.ClientShaftAngles;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.google.gson.Gson;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;

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
    private final java.util.Set<BlockPos> paidSteam = new java.util.HashSet<>();
    private record Phase(int tick, Double input, Double output, Double mounted) {}
    private record RotorFrame(String position, String block, double rpm, Double input, Double output, Double mounted,
        float target, boolean source, long revision) {}
    private record SectionFrame(int id, double fade, Vec3 offset, Vec3 rotation, List<String> blocks) {}
    private record Frame(int tick, int sceneTime, int sceneDuration, boolean finished, List<String> captions, List<RotorFrame> rotors, List<SectionFrame> sections, Map<String, String> changes,
        Map<String, List<Double>> mechanisms) {}
    private record WheelPose(double x, double angle, double speed) {}

    private static final Field SELECTION = field(WorldSectionElementImpl.class, "section");
    private static final Field FADE = field(AnimatedSceneElementBase.class, "fade");
    private static final Field ROTORS = field(PonderKinetics.class, "rotors");
    private static final Field CAPTION = field(net.createmod.ponder.foundation.element.TextWindowElement.class, "textGetter");
    private static final Field PORT_ANIMATION = field(com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class, "connectionAnimation");
    private final Map<com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity, Float> portAnimations = new IdentityHashMap<>();
    private int elapsedTicks;
    private int pumpStartupSamples;
    private boolean pumpStarted;
    private double pumpStartupPeak, pumpSettledMin = Double.POSITIVE_INFINITY, pumpSettledMax;
    private final Map<KineticBlockEntity, Phase> phases = new IdentityHashMap<>();
    private final Map<WorldSectionElement, Integer> sectionIds = new IdentityHashMap<>();
    private final Map<BlockPos, String> blocks = new LinkedHashMap<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<BlockPos, WheelPose> wheels = new LinkedHashMap<>();
    private final Map<String, Integer> steamContacts = new LinkedHashMap<>();
    private final Map<String, Double> rigidPhases = new LinkedHashMap<>();
    private final Map<String, Integer> rigidAges = new LinkedHashMap<>();
    private final Map<String, Integer> gearshiftAges = new LinkedHashMap<>();
    private final PonderFanAudit fans = new PonderFanAudit();
    private final PonderShaftAudit shafts = new PonderShaftAudit();
    private final PonderTransmissionAudit transmissions = new PonderTransmissionAudit();
    private final PonderMaterialAudit materials = new PonderMaterialAudit();
    private final PonderReadoutAudit readouts = new PonderReadoutAudit();
    private final PonderOutcomeAudit outcomes = new PonderOutcomeAudit();
    private final PonderTransitionAudit transitions = new PonderTransitionAudit();
    private final PonderClarityAudit clarity = new PonderClarityAudit();
    private final PonderTimingAudit timing = new PonderTimingAudit();

    void sample(PonderScene scene) {
        timing.observe(scene);
        pumpStartup(scene);
        transitions.sample(scene);
        outcomes.sample(scene);
        readouts.sample(scene);
        materials.sample(scene);
        fans.sample(scene);
        shafts.sample(scene);
        transmissions.sample(scene);
        gearshiftDirections(scene);
        int tick = ++elapsedTicks;
        var physics = PonderKinetics.of(scene.getWorld());
        var rotors = new ArrayList<RotorFrame>();
        for (var be : scene.getWorld().getBlockEntities()) {
            if (be instanceof com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity port) {
                try {
                    var animation = (LerpedFloat) PORT_ANIMATION.get(port);
                    float start = animation.getValue(0), end = animation.getValue(1);
                    Float previous = portAnimations.put(port, end);
                    if (previous != null && Math.abs(previous - start) > 1e-6 && failures.size() < 12)
                        failures.add("Portable interface interpolation resets at tick " + scene.getCurrentTime()
                            + " " + port.getBlockPos().toShortString() + ": " + previous + " -> " + start + " -> " + end);
                    for (float fraction : new float[]{0, 1 / 3f, 2 / 3f, 1}) {
                        float value = animation.getValue(fraction);
                        if ((!Float.isFinite(value) || value < -.000001f || value > 1.000001f
                            || Math.abs(value - (start + fraction * (end - start))) > 1e-6) && failures.size() < 12)
                            failures.add("Invalid portable interface subframe at " + port.getBlockPos());
                    }
                } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            }
            if (!(be instanceof KineticBlockEntity kinetic)) continue;
            Double input = physics.angle(kinetic, 1), mounted = physics.outputAngle(kinetic, 1);
            Double output = output(physics, kinetic, 1);
            var previous = phases.put(kinetic, new Phase(tick, input, output, mounted));
            if (previous != null && previous.tick() == tick - 1) {
                // A part that just joined a running assembly is deliberately moved onto its
                // visible phase; that one correction is what removes a fixed joint twist.
                if (!physics.resynchronized(kinetic))
                    continuous(scene, kinetic, "input", previous.input(), physics.angle(kinetic, 0));
                if (!physics.outputResynchronized(kinetic))
                    continuous(scene, kinetic, "output", previous.output(), output(physics, kinetic, 0));
                continuous(scene, kinetic, "mounted", previous.mounted(), physics.outputAngle(kinetic, 0));
            }
            if (Boolean.getBoolean("differentialcreate.ponderAudit")) {
                try {
                    var rotor = ((Map<?, ?>) ROTORS.get(physics)).get(be.getBlockPos());
                    float target = rotor == null ? 0 : field(rotor.getClass(), "target").getFloat(rotor);
                    long revision = rotor == null ? 0 : field(rotor.getClass(), "commandRevision").getLong(rotor);
                    var source = rotor == null ? null : rotor.getClass().getDeclaredMethod("source");
                    if (source != null) source.setAccessible(true);
                    rotors.add(new RotorFrame(be.getBlockPos().toShortString(), be.getBlockState().toString(),
                        kinetic.getSpeed(), input, output, mounted, target,
                        source != null && (boolean) source.invoke(rotor), revision));
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
        }
        var sections = new ArrayList<SectionFrame>();
        var changes = new LinkedHashMap<String, String>();
        var mechanisms = new LinkedHashMap<String, List<Double>>();
        mechanisms.putAll(com.diliupengyun.differential_create.client.ponder.PonderSimulation.of(scene.getWorld()).readings());
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
                            // The production service brake is 8 m/s² = .02 blocks/tick².
                            if (Math.abs(speed - oldWheel.speed()) > com.diliupengyun.differential_create.trains.TrainMechanics.BRAKE_ACCELERATION / 400 + .001 && failures.size() < 12)
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
                    && offset.distanceTo(section.offset()) > .01 && failures.size() < 12)
                    failures.add("Minecart and cargo separate at tick " + tick);
        });
        steamAssembly(scene, sections, mechanisms);
        for (var be : scene.getWorld().getBlockEntities()) {
            String pos = be.getBlockPos().toShortString();
            if (be instanceof com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity port) {
                try {
                    var animation = (LerpedFloat) PORT_ANIMATION.get(port);
                    mechanisms.put("port animation " + pos, List.of((double) animation.getValue(0),
                        (double) animation.getValue(1 / 3f), (double) animation.getValue(2 / 3f),
                        (double) animation.getValue(1), port.isTransferring() ? 1d : 0d));
                    if (scene.getId().toString().equals("create:portable_fluid_interface")
                        && (be.getBlockPos().equals(new BlockPos(2, 2, 2)) || be.getBlockPos().equals(new BlockPos(4, 2, 2)))
                        && scene.getWorld().getBlockEntity(new BlockPos(2, 2, 2))
                            instanceof com.diliupengyun.differential_create.kinetics.portable.PortableEndpoint station
                        && station.differentialcreate$docking().phase
                            == com.diliupengyun.differential_create.kinetics.portable.DockingState.Phase.TRANSFER
                        && (animation.getValue(0) != 1 || animation.getValue(1) != 1 || !port.isTransferring())
                        && failures.size() < 12)
                        failures.add("Both connected interface heads must stay extended throughout every frame: " + pos);
                } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            }
            if (be instanceof com.simibubi.create.content.fluids.hosePulley.HosePulleyBlockEntity hose)
                mechanisms.put("hose " + pos, List.of((double) hose.getInterpolatedOffset(1), (double) hose.getSpeed()));
            if (be instanceof com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity shaft
                && com.diliupengyun.differential_create.kinetics.SourcePhysics.steamCurve(shaft).stallTorque() != 0)
                paidSteam.add(be.getBlockPos().immutable());
            if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity crusher)
                mechanisms.put("crusher " + pos, List.of((double) crusher.crushingspeed,
                    crusher.isVirtual() ? 1d : 0d, crusher.hasEntity() ? 1d : 0d,
                    (double) crusher.inventory.getStackInSlot(0).getCount(), (double) crusher.inventory.remainingTime));
            if (be instanceof com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity press) {
                var pressing = press.getPressingBehaviour();
                mechanisms.put("press " + pos, List.of((double) pressing.runningTicks,
                    pressing.running ? 1d : 0d, (double) press.getSpeed()));
            }
            if (be instanceof com.simibubi.create.content.logistics.depot.DepotBlockEntity depot) {
                var inventory = depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    var stack = inventory.getStackInSlot(slot);
                    if (!stack.isEmpty()) mechanisms.put("depot " + pos + " #" + slot + " "
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()), List.of((double) stack.getCount()));
                }
            }
            if (Boolean.getBoolean("differentialcreate.ponderAudit")
                && be instanceof com.simibubi.create.content.kinetics.belt.BeltBlockEntity belt && belt.isController()) {
                int index = 0;
                for (var item : belt.getInventory().getTransportedItems())
                    mechanisms.put("beltitem " + pos + " #" + index++ + " " + item.stack, List.of(
                        (double) item.beltPosition, (double) item.prevBeltPosition, item.locked ? 1d : 0d));
            }
            if (be instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank && tank.isController()) {
                mechanisms.put("boiler " + pos, List.of((double) tank.getWidth(), (double) tank.getHeight(),
                    Boolean.TRUE.equals(tank.getExtraData()) ? 1d : 0d, tank.boiler.isActive() ? 1d : 0d,
                    (double) tank.boiler.activeHeat, com.diliupengyun.differential_create.kinetics.BoilerAdmission.fraction(tank.boiler),
                    (double) tank.boiler.gauge.getValue(1), (double) tank.getTankInventory().getFluidAmount(),
                    com.diliupengyun.differential_create.kinetics.BoilerAdmission.demand(tank.boiler), (double) tank.boiler.waterSupply));
                var level = tank.getFluidLevel();
                if (level != null)
                    mechanisms.put("tank level " + pos, List.of((double) tank.getFillState(),
                        (double) level.getValue(0), (double) level.getValue(1), (double) level.getChaseTarget()));
            }
            if (be instanceof com.simibubi.create.content.processing.basin.BasinBlockEntity basin) {
                var stock = basin.inputTank.getCapability();
                for (int slot = 0; slot < stock.getTanks(); slot++) {
                    var fluid = stock.getFluidInTank(slot);
                    mechanisms.put("basin " + pos + " #" + slot + " "
                        + net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid()),
                        List.of((double) fluid.getAmount()));
                }
            }
            if (be instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) {
                var pipe = smart.getBehaviour(com.simibubi.create.content.fluids.FluidTransportBehaviour.TYPE);
                if (pipe != null) {
                    var fluid = com.diliupengyun.differential_create.kinetics.FluidKinetics.state(pipe);
                    mechanisms.put("pipe " + pos, List.of(fluid.volume(), fluid.airVolume(), fluid.pumpFlow,
                        fluid.outward.values().stream().mapToDouble(Double::doubleValue).sum()));
                }
            }
            if (be instanceof com.simibubi.create.content.logistics.depot.EjectorBlockEntity ejector) {
                var target = ejector.getTargetPosition();
                mechanisms.put("ejector " + pos, List.of((double) target.getX(), (double) target.getY(), (double) target.getZ(),
                    (double) ejector.getState().ordinal()));
            }
        }
        if (Boolean.getBoolean("differentialcreate.ponderAudit"))
            for (var entity : scene.getWorld().getEntityList())
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity item)
                    mechanisms.put("item " + entity.getId() + " " + item.getItem(), List.of(
                        item.getX(), item.getY(), item.getZ(), item.getDeltaMovement().x,
                        item.getDeltaMovement().y, item.getDeltaMovement().z));
        scene.forEachVisible(com.diliupengyun.differential_create.client.ponder.PonderHoseElement.class, hose -> {
            var connection = hose.connection(scene.getWorld(), 1);
            if (connection != null) mechanisms.put("hose " + hose.enginePos().toShortString(), List.of(
                connection.engine().point().x, connection.engine().point().y, connection.engine().point().z,
                connection.tank().point().x, connection.tank().point().y, connection.tank().point().z));
        });
        var captions = new ArrayList<String>();
        scene.forEachVisible(net.createmod.ponder.foundation.element.TextWindowElement.class, text -> {
            try { captions.add(((java.util.function.Supplier<?>) CAPTION.get(text)).get().toString()); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        });
        clarity.sample(scene, captions);
        if (Boolean.getBoolean("differentialcreate.ponderAudit")) {
            frames.add(new Frame(tick, scene.getCurrentTime(), scene.getTotalTime(), scene.isFinished(), captions, rotors, sections, changes, mechanisms));
        }
        rigidContacts(scene, sections);
    }

    /**
     * Every settled rigid contact — adjacent along one axis, same axis, ratio 1, no cog teeth in
     * between — renders both of its ends at one phase, whichever order the scene revealed them in.
     * A member that merged into a running assembly while keeping a foreign phase (a gauge revealed
     * before the shafts it later connects to) leaves a fixed offset here. Mod 90 matches the
     * four-fold shaft model, as in the steam assembly check.
     * Positions come from the displayed sections, matching PonderDisplayTopology: a moved section
     * can show its blocks elsewhere, and a coordinate owned by two sections has one visible pose.
     */
    private void rigidContacts(PonderScene scene, List<SectionFrame> sections) {
        var physics = PonderKinetics.of(scene.getWorld());
        var displayed = new LinkedHashMap<KineticBlockEntity, BlockPos>();
        for (var section : sections) {
            if (section.fade() < .999 || !section.rotation().equals(Vec3.ZERO)) continue;
            var offset = section.offset();
            if (Math.abs(offset.x - Math.round(offset.x)) > 1e-6 || Math.abs(offset.y - Math.round(offset.y)) > 1e-6
                || Math.abs(offset.z - Math.round(offset.z)) > 1e-6) continue;
            var shift = new BlockPos((int) Math.round(offset.x), (int) Math.round(offset.y), (int) Math.round(offset.z));
            for (var coordinates : section.blocks()) {
                String[] parts = coordinates.split(", ");
                var original = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                var be = scene.getWorld().getBlockEntity(original);
                if (be instanceof KineticBlockEntity member && physics.angle(member, 1) != null)
                    displayed.put(member, original.offset(shift));
            }
        }
        var members = new ArrayList<>(displayed.entrySet());
        var present = new java.util.HashSet<String>();
        for (int i = 0; i < members.size(); i++) for (int j = i + 1; j < members.size(); j++) {
            var a = members.get(i).getKey();
            var b = members.get(j).getKey();
            if (a.getBlockPos().compareTo(b.getBlockPos()) >= 0) continue;
            var axis = KineticBlockEntityRenderer.getRotationAxisOf(a);
            if (axis != KineticBlockEntityRenderer.getRotationAxisOf(b)) continue;
            var delta = members.get(j).getValue().subtract(members.get(i).getValue());
            if (delta.distSqr(BlockPos.ZERO) != 1
                || Math.abs(axis.choose(delta.getX(), delta.getY(), delta.getZ())) != 1) continue;
            if (!physics.sameComponent(a, b)) continue;
            boolean splitA = a instanceof SplitShaftBlockEntity;
            boolean splitB = b instanceof SplitShaftBlockEntity;
            // Split shafts expose one rendered face per side; compare that face, not the
            // block's overall angle. PonderShaftAudit separately checks its vertices.
            if (!splitA && !splitB && NetworkTopology.connection(a, b) != 1f) continue;
            if ((splitA || splitB) && NetworkTopology.connection(a, b) == 0f) continue;
            String key = a.getBlockPos() + " / " + b.getBlockPos();
            present.add(key);
            var direction = net.minecraft.core.Direction.fromAxisAndDirection(axis,
                axis.choose(delta.getX(), delta.getY(), delta.getZ()) > 0
                    ? net.minecraft.core.Direction.AxisDirection.POSITIVE
                    : net.minecraft.core.Direction.AxisDirection.NEGATIVE);
            float angleA = splitA
                ? ClientShaftAngles.at(a, direction).radians()
                : ClientKineticAngles.shaftAngleOf(a);
            float angleB = splitB
                ? ClientShaftAngles.at(b, direction.getOpposite()).radians()
                : ClientKineticAngles.shaftAngleOf(b);
            double phase = Math.toDegrees(angleA - angleB);
            double previous = rigidPhases.getOrDefault(key, Double.NaN);
            rigidPhases.put(key, phase);
            // A rotor settling onto its assembly phase travels through this pair; only an
            // offset that stops moving is the defect (a member that kept a foreign phase).
            int age = Math.abs(Math.IEEEremainder(phase, 90)) > .2 && Math.abs(phase - previous) < .02
                ? rigidAges.merge(key, 1, Integer::sum) : rigidAges.merge(key, 0, (old, one) -> 0);
            if (age >= 20 && failures.size() < 12)
                failures.add("Rigid contact holds two phases at tick " + scene.getCurrentTime() + ": "
                    + key + " phase " + phase);
        }
        rigidPhases.keySet().retainAll(present);
        rigidAges.keySet().retainAll(present);
    }

    /**
     * A redstone gearshift changes only its output side. Reversing both sides (the
     * native template's stale self-source did this) leaves the same relative motion
     * and makes the lesson look like a whole-line reversal.
     */
    private void gearshiftDirections(PonderScene scene) {
        var present = new java.util.HashSet<String>();
        for (var be : scene.getWorld().getBlockEntities()) {
            if (!(be instanceof com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity gearshift)
                || gearshift.isRemoved() || !gearshift.hasSource()) continue;
            var level = scene.getWorld();
            var pos = gearshift.getBlockPos();
            var face = gearshift.getSourceFacing();
            if (!(level.getBlockEntity(pos.relative(face)) instanceof KineticBlockEntity input)
                || !(level.getBlockEntity(pos.relative(face.getOpposite())) instanceof KineticBlockEntity output))
                continue;
            float in = input.getSpeed(), out = output.getSpeed();
            if (Math.abs(in) < 1 || Math.abs(out) < 1) continue;
            boolean powered = gearshift.getBlockState()
                .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
            boolean correct = powered != (Math.signum(in) == Math.signum(out));
            String key = pos.toShortString();
            present.add(key);
            int age = correct ? 0 : gearshiftAges.merge(key, 1, Integer::sum);
            gearshiftAges.put(key, age);
            if (age >= 20 && failures.size() < 12)
                failures.add("Gearshift does not reverse only its output at tick " + scene.getCurrentTime()
                    + ": " + key + " input " + in + " output " + out + " powered " + powered);
        }
        gearshiftAges.keySet().retainAll(present);
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
                && (!paidSteam.contains(be.getBlockPos()) || Math.abs(be.getSpeed()) < 1) && failures.size() < 12)
                failures.add("Native steam output has no paid startup: " + be.getBlockPos() + " at " + be.getSpeed());
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
            + "-" + component + ".json"), new Gson().toJson(Map.of("frames", frames, "failures", failures, "transitions", transitions.report())));
    }

    private void pumpStartup(PonderScene scene) {
        if (!scene.getId().toString().equals("differentialcreate:pump_network")) return;
        var world = scene.getWorld();
        // The pipe starts dry and stopped; its only drive operation starts the pump.
        if (!AllBlocks.GLASS_FLUID_PIPE.has(world.getBlockState(new BlockPos(3, 1, 2)))
            || !(world.getBlockEntity(new BlockPos(2, 1, 2)) instanceof KineticBlockEntity pump)) return;
        double rpm = Math.abs(pump.getSpeed());
        if (!Double.isFinite(rpm)) throw new AssertionError("Pipe lesson starts with a non-finite pump speed");
        if (!pumpStarted && rpm < .1) return;
        pumpStarted = true;
        pumpStartupPeak = Math.max(pumpStartupPeak, rpm);
        if (++pumpStartupSamples > 40) {
            pumpSettledMin = Math.min(pumpSettledMin, rpm);
            pumpSettledMax = Math.max(pumpSettledMax, rpm);
        }
    }

    void verify(PonderScene scene) {
        if (scene.getId().toString().equals("differentialcreate:pump_network")
            && (pumpStartupSamples <= 40 || pumpStartupPeak > 10 || pumpSettledMin < 7.5
                || pumpSettledMax - pumpSettledMin > .1))
            throw new AssertionError("Pipe lesson must start once and run steadily, without an unexplained fast-to-slow change: peak="
                + pumpStartupPeak + ", settled=" + pumpSettledMin + ".." + pumpSettledMax);
        transitions.verify(scene);
        outcomes.verify(scene);
        clarity.verify(scene);
        readouts.verify(scene);
        materials.verify(scene);
        fans.verify(scene);
        shafts.verify(scene);
        transmissions.verify(scene);
        if (scene.getId().toString().equals("create:fluid_pipe_interaction")) PonderTransitionAudit.verifySourceOverlapDetector(scene);
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

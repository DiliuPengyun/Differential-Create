package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import com.diliupengyun.differential_create.client.ponder.PonderHoseElement;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.NetworkTopology;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Cross-check visible construction, live machine state and the actions claimed by a lesson. */
final class PonderSceneStateAudit {
    private record Shown(Block block, double fade, Vec3 offset) {}
    private final Map<BlockPos, Shown> previous = new HashMap<>();
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private double maximumSteamRpm;
    private boolean steamCoast, steamGauge, trainSupply, trainHose, trainFuel;
    private boolean unequalCrushing;
    private final java.util.Set<BlockPos> ejectorTargets = new HashSet<>();
    private static final Field FADE;
    static {
        try { FADE = AnimatedSceneElementBase.class.getDeclaredField("fade"); FADE.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    void sample(PonderScene scene) {
        var world = scene.getWorld();
        var shown = new HashMap<BlockPos, Shown>();
        scene.forEachVisible(WorldSectionElement.class, section -> {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection == null) return;
            double fade;
            try { fade = ((LerpedFloat) FADE.get(section)).getValue(1); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            for (var pos : selection) {
                var block = world.getBlockState(pos).getBlock();
                var old = previous.get(pos);
                if (old != null && old.block() != net.minecraft.world.level.block.Blocks.AIR
                    && old.fade() > .01 && old.block() != block)
                    fail(scene, "Visible block was replaced before sliding out at " + pos);
                shown.put(pos.immutable(), new Shown(block, fade, section.getAnimatedOffset()));
            }
        });
        for (var entry : shown.entrySet()) {
            if (entry.getValue().fade() < .999) continue;
            var be = world.getBlockEntity(entry.getKey());
            var state = world.getBlockState(entry.getKey());
            if (AllBlocks.GANTRY_SHAFT.has(state)) {
                var facing = state.getValue(GantryShaftBlock.FACING);
                var front = world.getBlockState(entry.getKey().relative(facing));
                var back = world.getBlockState(entry.getKey().relative(facing.getOpposite()));
                boolean joinedFront = AllBlocks.GANTRY_SHAFT.has(front) && front.getValue(GantryShaftBlock.FACING) == facing;
                boolean joinedBack = AllBlocks.GANTRY_SHAFT.has(back) && back.getValue(GantryShaftBlock.FACING) == facing;
                var expected = joinedFront ? (joinedBack ? GantryShaftBlock.Part.MIDDLE : GantryShaftBlock.Part.START)
                    : (joinedBack ? GantryShaftBlock.Part.END : GantryShaftBlock.Part.SINGLE);
                if (state.getValue(GantryShaftBlock.PART) != expected)
                    fail(scene, "Gantry rack model has disconnected end caps at " + entry.getKey());
            }
            if (scene.getId().getPath().startsWith("train_")) trainFuel |= hasMountedFuel(scene, be);
            if (be instanceof com.simibubi.create.content.logistics.depot.EjectorBlockEntity ejector)
                ejectorTargets.add(ejector.getTargetPosition());
            if (be instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity wheel)
                for (var otherEntry : shown.entrySet())
                    if (otherEntry.getValue().fade() >= .999 && world.getBlockEntity(otherEntry.getKey())
                        instanceof com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity other && wheel != other) {
                        var pair = new com.diliupengyun.differential_create.kinetics.CrushingKinetics.Pair(null, wheel, other);
                        unequalCrushing |= wheel.getSpeed() * other.getSpeed() > 0
                            && Math.abs(wheel.getSpeed() - other.getSpeed()) > 5 && pair.facing() == Direction.DOWN;
                    }
            if (be instanceof FluidTankBlockEntity tank && tank.isController()) {
                if (tank.boiler.isActive() && Boolean.TRUE.equals(tank.getExtraData()))
                    fail(scene, "Active boiler still renders tank windows and liquid at " + tank.getBlockPos());
                for (int x = 0; x < tank.getWidth(); x++) for (int z = 0; z < tank.getWidth(); z++)
                    for (int y = 0; y < tank.getHeight(); y++) {
                        var pos = tank.getBlockPos().offset(x, y, z);
                        var partState = world.getBlockState(pos);
                        if (!(world.getBlockEntity(pos) instanceof FluidTankBlockEntity part)
                            || part.getControllerBE() != tank) {
                            fail(scene, "Tank member does not resolve its displayed controller at " + pos); continue;
                        }
                        if (partState.getValue(FluidTankBlock.TOP) != (y == tank.getHeight() - 1)
                            || partState.getValue(FluidTankBlock.BOTTOM) != (y == 0))
                            fail(scene, "Tank top/bottom seams disagree with its multiblock at " + pos);
                        if (tank.boiler.isActive() && partState.getValue(FluidTankBlock.SHAPE) != FluidTankBlock.Shape.PLAIN)
                            fail(scene, "Boiler member has a glass-window block model at " + pos);
                    }
                steamGauge |= tank.boiler.isActive() && tank.boiler.gauge.getValue(1) > .25;
            }
            if (be instanceof PumpBlockEntity pump && !hasVisibleMotor(scene, pump, shown))
                fail(scene, "Pump is animated without a connected visible drive at " + pump.getBlockPos());
            if (be instanceof SteamEngineBlockEntity engine) {
                var tank = engine.getTank(); var shaft = engine.getShaft();
                if (tank == null || shaft == null || !engine.isValid()) {
                    fail(scene, "Steam engine is not attached to a valid boiler and crank at " + engine.getBlockPos()); continue;
                }
                var curve = SourcePhysics.steamCurve(shaft);
                if (tank.boiler.activeHeat > 0 && BoilerAdmission.fraction(tank.boiler) > 0) {
                    if (curve.stallTorque() == 0 || shaft.engineEfficiency == 0)
                        fail(scene, "Heated, supplied boiler disagrees with its engine state at " + engine.getBlockPos());
                    maximumSteamRpm = Math.max(maximumSteamRpm, Math.abs(shaft.getSpeed()));
                } else if (curve.stallTorque() == 0 && Math.abs(shaft.getSpeed()) > 10) steamCoast = true;
            }
            if (be instanceof AbstractBogeyBlockEntity bogey) {
                var axle = state.getValue(com.simibubi.create.content.trains.bogey.AbstractBogeyBlock.AXIS);
                var direction = Direction.get(Direction.AxisDirection.POSITIVE, axle);
                for (int side : new int[]{-1, 1}) {
                    var contact = BlockPos.containing(Vec3.atCenterOf(entry.getKey().relative(direction, side))
                        .add(entry.getValue().offset())).below();
                    if (!AllBlocks.TRACK.has(world.getBlockState(contact)))
                        fail(scene, "Displayed train wheel has left the track at " + contact);
                }
                if (!SteamBogey.powered(bogey)) continue;
                var tank = SteamHoseLinks.tank(bogey);
                boolean supplied = tank != null && tank.getTotalTankSize() >= 4 && tank.boiler.isActive()
                    && tank.boiler.activeHeat > 0 && !tank.getTankInventory().getFluid().isEmpty();
                trainSupply |= supplied;
                var old = previous.get(entry.getKey());
                if (old != null && old.offset().distanceToSqr(entry.getValue().offset()) > 1e-10 && !supplied)
                    fail(scene, "Moving powered train has no valid boiler/hose/water supply");
                if (supplied && old != null && old.offset().distanceToSqr(entry.getValue().offset()) > 1e-10)
                    trainAssembly(scene, tank, shown, entry.getValue().offset());
            }
        }
        scene.forEachVisible(PonderHoseElement.class, hose -> {
            var connection = hose.connection(world, 1);
            if (connection == null) { fail(scene, "Installed Ponder hose has no visible endpoints"); return; }
            trainHose = true;
            var engine = world.getBlockEntity(hose.enginePos());
            var link = (SteamHoseLinks.EngineLink) engine;
            BlockPos end = hose.enginePos().offset(link.differentialcreate$hose());
            var originShown = shown.get(hose.enginePos()); var endShown = shown.get(end);
            if (originShown != null && endShown != null) {
                var from = Vec3.atLowerCornerOf(hose.enginePos()).add(.5, 1.015, .5).add(originShown.offset());
                var to = Vec3.atLowerCornerOf(end).add(link.differentialcreate$port().point())
                    .add(link.differentialcreate$port().normal().scale(.015)).add(endShown.offset());
                if (from.distanceToSqr(connection.engine().point()) > 1e-9 || to.distanceToSqr(connection.tank().point()) > 1e-9)
                    fail(scene, "Hose endpoints do not follow the displayed bogey and boiler");
            }
            for (var point : connection.points())
                if (!Double.isFinite(point.lengthSqr())) fail(scene, "Non-finite installed hose geometry");
            if (originShown != null && endShown != null && originShown.offset().equals(endShown.offset()))
                for (var point : connection.points()) {
                    var local = point.subtract(originShown.offset());
                    var pos = BlockPos.containing(local);
                    var owner = shown.get(pos);
                    if (owner == null || owner.fade() < .999 || !owner.offset().equals(originShown.offset())) continue;
                    var inside = local.subtract(Vec3.atLowerCornerOf(pos));
                    if (world.getBlockState(pos).getCollisionShape(world, pos).toAabbs().stream()
                        .anyMatch(box -> box.contains(inside)))
                        fail(scene, "Displayed steam hose passes through a carriage block at " + pos);
                }
            if (connection.points().getFirst().distanceToSqr(connection.engine().point()) > 1e-10
                || connection.points().getLast().distanceToSqr(connection.tank().point()) > 1e-10)
                fail(scene, "Hose leaves its displayed attachment while moving");
        });
        previous.clear(); previous.putAll(shown);
    }

    private void trainAssembly(PonderScene scene, FluidTankBlockEntity tank, Map<BlockPos, Shown> shown, Vec3 offset) {
        var world = scene.getWorld();
        double heat = 0;
        for (int x = 0; x < tank.getWidth(); x++) for (int z = 0; z < tank.getWidth(); z++) {
            for (int y = 0; y < tank.getHeight(); y++) {
                var part = shown.get(tank.getBlockPos().offset(x, y, z));
                if (part == null || part.fade() < .999 || part.offset().distanceToSqr(offset) > 1e-10)
                    fail(scene, "Moving train leaves part of its boiler behind");
            }
            var pos = tank.getBlockPos().offset(x, -1, z);
            var heater = shown.get(pos);
            if (heater != null && heater.fade() >= .999 && heater.offset().distanceToSqr(offset) < 1e-10)
                heat += Math.max(0, com.simibubi.create.api.boiler.BoilerHeater.findHeat(world, pos, world.getBlockState(pos)));
        }
        if (heat < tank.boiler.activeHeat) fail(scene, "Moving boiler lacks its displayed heat sources");
        boolean carriedFuel = false, carriedWater = false;
        for (var entry : shown.entrySet()) {
            if (entry.getValue().fade() < .999 || entry.getValue().offset().distanceToSqr(offset) > 1e-10) continue;
            var be = world.getBlockEntity(entry.getKey());
            carriedFuel |= hasMountedFuel(scene, be);
            if (be instanceof FluidTankBlockEntity reserve && reserve.isController() && !reserve.boiler.isActive())
                carriedWater |= reserve.getTankInventory().getFluid().getFluid() == net.minecraft.world.level.material.Fluids.WATER
                    && reserve.getTankInventory().getFluidAmount() > 0;
        }
        if (!carriedFuel) fail(scene, "Moving train leaves its fuel container behind");
        if (!carriedWater) fail(scene, "Moving train has no separate carried water reserve");
    }

    /** Ask the native mounted inventory which fuel is usable; combustible cargo alone is insufficient. */
    private static boolean hasMountedFuel(PonderScene scene, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be instanceof net.minecraft.world.Container)
            && !(be instanceof com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity)) return false;
        var storage = new com.simibubi.create.content.contraptions.MountedStorageManager();
        storage.addBlock(scene.getWorld(), be.getBlockState(), be.getBlockPos(), be.getBlockPos(), be);
        storage.initialize();
        var fuel = storage.getFuelItems();
        if (fuel == null) return false;
        for (int slot = 0; slot < fuel.getSlots(); slot++)
            if (fuel.getStackInSlot(slot).getBurnTime(null) > 0) return true;
        return false;
    }

    private static boolean hasVisibleMotor(PonderScene scene, KineticBlockEntity start, Map<BlockPos, Shown> shown) {
        var visited = new HashSet<BlockPos>();
        var queue = new ArrayDeque<KineticBlockEntity>(); queue.add(start);
        while (!queue.isEmpty()) {
            var be = queue.removeFirst();
            if (!visited.add(be.getBlockPos())) continue;
            if (be instanceof CreativeMotorBlockEntity) return true;
            for (var direction : Direction.values()) {
                var pos = be.getBlockPos().relative(direction);
                if (shown.containsKey(pos) && scene.getWorld().getBlockEntity(pos) instanceof KineticBlockEntity other
                    && (NetworkTopology.connection(be, other) != 0 || NetworkTopology.connection(other, be) != 0)) queue.add(other);
            }
        }
        return false;
    }

    private void fail(PonderScene scene, String message) {
        if (failures.size() < 12) failures.putIfAbsent(message, scene.getCurrentTime());
    }

    void verify(PonderScene scene) {
        if (scene.getId().getPath().equals("steam_power")) {
            if (!steamGauge) fail(scene, "Steam lesson never displays an operating boiler gauge");
            if (maximumSteamRpm < 480) fail(scene, "Governor lesson never approaches the governor: " + maximumSteamRpm + " RPM");
            if (!steamCoast) fail(scene, "Steam lesson never shows coasting with steam actually cut off");
        }
        if (scene.getId().getPath().startsWith("train_")) {
            if (!trainSupply) fail(scene, "Train lesson never has a connected boiler and water supply");
            if (!trainHose) fail(scene, "Train lesson never renders its installed hose");
            if (!trainFuel) fail(scene, "Train lesson has no fuel accessible through its displayed mounted storage");
        }
        if (scene.getId().getPath().equals("crusher_work") && !unequalCrushing)
            fail(scene, "Crusher lesson never demonstrates unequal same-way rotation feeding into the gap");
        if (scene.getId().getPath().equals("ejector_work") && ejectorTargets.size() < 2)
            fail(scene, "Ejector retargeting lesson never changes the actual landing target");
        if (!failures.isEmpty()) throw new AssertionError("Inconsistent scene state in " + scene.getId() + ": " + failures);
    }
}

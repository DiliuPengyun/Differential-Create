package com.diliupengyun.differential_create.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.AnimatedSceneElementBase;
import net.createmod.ponder.foundation.element.ParrotElementImpl;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Check the rendered seating position, carriage spacing and relocation itself,
 * independently of the implementation's attachment maps or driver commands. */
final class PonderTrainVisualAudit {
    private static final Field LOCATION = field(ParrotElementImpl.class, "location");
    private static final Field ENTITY = field(ParrotElementImpl.class, "entity");
    private static final Field FADE = field(AnimatedSceneElementBase.class, "fade");
    private int ridingSamples, coupledSamples;
    private double maximumLift, maximumTurn, maximumTravel, secondTravel, departureTravel;
    private boolean relocatedOut, relocatedBack;
    private final int[] observerPasses = new int[3];

    void sample(PonderScene scene) {
        String name = scene.getId().getPath();
        if (!List.of("auto_schedule", "train_assembly", "train_schedule", "train_signal_placement", "train_signal_signaling", "track_observer").contains(name)) return;
        var sections = new ArrayList<WorldSectionElement>();
        var passengers = new ArrayList<ParrotElement>();
        scene.forEachVisible(WorldSectionElement.class, section -> {
            if (shown(section) || name.equals("train_signal_signaling")) sections.add(section);
        });
        scene.forEachVisible(ParrotElement.class, passenger -> { if (shown(passenger)) passengers.add(passenger); });
        if (name.equals("track_observer")) {
            var lead = containing(sections, new BlockPos(5, 2, 7));
            if (lead == null) return;
            for (var passenger : passengers) seat(scene, lead, passenger, new Vec3(6.5, 3.5, 7.5));
            maximumTravel = Math.max(maximumTravel, 1 - lead.getAnimatedOffset().x);
            for (float partial : new float[]{0, .25f, .5f, .75f, 1})
                require(5.5 + offset(lead, partial).x >= .5, scene,
                    "fully visible observer train ran beyond the illustrated track");
            for (int i = 0; i < 3; i++) {
                var trailer = containing(sections, new BlockPos(9, 2, 7 - i * 3));
                if (trailer == null) continue;
                onTrack(scene, trailer, 9.5, 10.5);
                for (float partial : new float[]{0, .25f, .5f, .75f, 1})
                    require(offset(trailer, partial).subtract(offset(lead, partial)).distanceTo(new Vec3(0, 0, i * 3)) < 1e-5,
                        scene, "observer lesson uncoupled its trailer");
                double x = lead.getAnimatedOffset().x;
                if (x < -.5 && x > -3.5) {
                    boolean powered = scene.getWorld().getBlockState(new BlockPos(5, 1, 4))
                        .getValue(com.simibubi.create.content.trains.observer.TrackObserverBlock.POWERED);
                    if (powered != (i != 2))
                        require(false, scene, "observer output disagrees with passing cargo: trailer=" + i
                            + ", x=" + x + ", powered=" + powered + ", tanks=" + tankContents(scene, sections));
                    observerPasses[i]++;
                }
            }
        } else if (name.equals("train_signal_placement")) {
            var train = containing(sections, new BlockPos(3, 2, 6));
            if (train != null) {
                maximumTravel = Math.max(maximumTravel, train.getAnimatedOffset().x);
                for (var passenger : passengers) seat(scene, train, passenger, new Vec3(2.5, 3.5, 6.5));
            }
        } else if (name.equals("train_signal_signaling")) {
            var first = containing(sections, new BlockPos(13, 2, 7));
            var second = containing(sections, new BlockPos(13, 2, 1));
            for (var passenger : passengers) {
                // The diagonal train crosses z=7.5 for one tick. Its rider is
                // moving in z and must not be assigned to either parked cab.
                if (Math.abs(position(passenger, 1).z - position(passenger, 0).z) > 1e-5) continue;
                var position = position(passenger, 1);
                if (Math.abs(position.z - 7.5) > 1e-5) continue; // Diagonal diagram, tested visually.
                double firstGap = first == null ? Double.POSITIVE_INFINITY : position.distanceTo(new Vec3(14.5, 3.5, 7.5).add(first.getAnimatedOffset()));
                double secondGap = second == null ? Double.POSITIVE_INFINITY : position.distanceTo(new Vec3(14.5, 3.5, 1.5).add(second.getAnimatedOffset()));
                if (firstGap <= secondGap && first != null && shown(first)) seat(scene, first, passenger, new Vec3(14.5, 3.5, 7.5));
                else if (secondGap < firstGap && second != null && shown(second)) seat(scene, second, passenger, new Vec3(14.5, 3.5, 1.5));
            }
            if (first != null && first.getAnimatedOffset().x > -6)
                maximumTravel = Math.max(maximumTravel, 4 - first.getAnimatedOffset().x);
            if (second != null) secondTravel = Math.max(secondTravel, 4 - second.getAnimatedOffset().x);
        } else if (name.equals("auto_schedule")) {
            var train = containing(sections, new BlockPos(7, 2, 6));
            if (train == null) return;
            var offset = train.getAnimatedOffset(); var rotation = train.getAnimatedRotation();
            maximumLift = Math.max(maximumLift, offset.y);
            maximumTurn = Math.max(maximumTurn, Math.abs(rotation.y));
            maximumTravel = Math.max(maximumTravel, offset.x);
            if (Math.abs(offset.y) < 1e-6 && rotation.lengthSqr() < 1e-6) {
                departureTravel = Math.max(departureTravel, offset.x);
                onTrack(scene, train, 7.5, 13.5);
                for (var passenger : passengers) seat(scene, train, passenger, new Vec3(6.5, 3.5, 6.5));
            }
        } else if (name.equals("train_assembly")) {
            var lead = containing(sections, new BlockPos(10, 2, 6));
            if (lead != null) {
                maximumTravel = Math.max(maximumTravel, lead.getAnimatedOffset().x);
                onTrack(scene, lead, 10.5, 13.5);
                for (var passenger : passengers) seat(scene, lead, passenger, new Vec3(10.5, 3.5, 6.5));
                if (lead.getAnimatedOffset().x > 1e-5) {
                    var controls = containing(sections, new BlockPos(10, 3, 6));
                    var trailer = containing(sections, new BlockPos(3, 2, 6));
                    if (controls != null && trailer != null) {
                        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
                            Vec3 shift = offset(lead, partial);
                            require(offset(controls, partial).subtract(shift).distanceTo(new Vec3(1, 0, 0)) < 1e-5,
                                scene, "control stand separates from its carriage");
                            require(offset(trailer, partial).distanceTo(shift) < 1e-5,
                                scene, "coupled carriages drift apart");
                        }
                        coupledSamples++;
                    }
                }
            }
            var relocating = containing(sections, new BlockPos(5, 3, 2));
            if (relocating != null) {
                relocatedOut |= relocating.getAnimatedOffset().z > 3.99;
                relocatedBack |= relocatedOut && Math.abs(relocating.getAnimatedOffset().z) < .01;
            }
        } else {
            var first = containing(sections, new BlockPos(4, 2, 4));
            if (first != null) {
                maximumTravel = Math.max(maximumTravel, first.getAnimatedOffset().x);
                onTrack(scene, first, 4.5, 10.5);
            }
            var second = containing(sections, new BlockPos(4, 2, 7));
            if (second != null) {
                secondTravel = Math.max(secondTravel, second.getAnimatedOffset().x);
                for (var passenger : passengers) if (position(passenger, 0).y > 3.49)
                    seat(scene, second, passenger, new Vec3(3.5, 3.5, 7.5));
            }
        }
    }

    private static void onTrack(PonderScene scene, WorldSectionElement train, double axle, double trackEnd) {
        for (float partial : new float[]{0, .25f, .5f, .75f, 1})
            require(axle + offset(train, partial).x <= trackEnd, scene,
                "fully visible departing train ran beyond the illustrated track");
    }

    private static String tankContents(PonderScene scene, List<WorldSectionElement> sections) {
        var contents = new ArrayList<String>();
        for (var section : sections) {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection == null) continue;
            for (var pos : selection)
                if (scene.getWorld().getBlockEntity(pos) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank)
                    contents.add(pos.toShortString() + ":" + tank.getTankInventory().getFluid());
        }
        return contents.toString();
    }

    private void seat(PonderScene scene, WorldSectionElement section, ParrotElement passenger, Vec3 seat) {
        for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            ((WorldSectionElementImpl) section).transformMS(pose, partial);
            Vec3 expected = new Vec3(pose.last().pose().transformPosition(seat.toVector3f()));
            double gap = position(passenger, partial).distanceTo(expected);
            require(gap < 2e-4, scene, "rider left the cab by " + gap + " at partial " + partial);
        }
        ridingSamples++;
    }
    private static Vec3 offset(WorldSectionElement section, float partial) {
        return ((PonderWorldSectionAccessor) section).differentialcreate$previousOffset().lerp(section.getAnimatedOffset(), partial);
    }
    private static Vec3 position(ParrotElement passenger, float partial) {
        try {
            var entity = (Entity) ENTITY.get(passenger);
            return ((Vec3) LOCATION.get(passenger)).add(new Vec3(entity.xo, entity.yo, entity.zo).lerp(entity.position(), partial));
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    private static boolean shown(Object element) {
        try { return ((LerpedFloat) FADE.get(element)).getValue(0) >= .999; }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    private static WorldSectionElement containing(List<WorldSectionElement> sections, BlockPos pos) {
        return sections.stream().filter(section -> ((PonderWorldSectionAccessor) section).differentialcreate$selection().test(pos))
            .findFirst().orElse(null);
    }
    void verify(PonderScene scene) {
        switch (scene.getId().getPath()) {
            case "track_observer" -> require(ridingSamples > 100 && maximumTravel > 4.5
                && java.util.Arrays.stream(observerPasses).allMatch(count -> count > 10),
                scene, "observer did not exercise coupled passage with matching and nonmatching cargo");
            case "train_signal_placement" -> require(ridingSamples > 30 && maximumTravel > 6.8,
                scene, "signal placement train or passenger did not reach the station");
            case "train_signal_signaling" -> require(ridingSamples > 100 && maximumTravel > 8.8 && secondTravel > 4.3,
                scene, "queued trains did not reach their signal stops with passengers");
            case "auto_schedule" -> require(ridingSamples > 40 && maximumLift > .99 && maximumTurn > 179
                && departureTravel > 2, scene, "train never completed arrival, lifting, turnaround and visible departure");
            case "train_assembly" -> require(ridingSamples > 20 && coupledSamples > 20 && maximumTravel > 2
                && relocatedBack, scene, "coupled train or wrench relocation did not complete");
            case "train_schedule" -> require(ridingSamples > 30 && maximumTravel > 3 && secondTravel > 2.8,
                scene, "scheduled trains did not reach their intended destinations");
        }
    }
    private static void require(boolean ok, PonderScene scene, String message) {
        if (!ok) throw new AssertionError(scene.getId() + " tick=" + scene.getCurrentTime() + ": " + message);
    }
    private static Field field(Class<?> type, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}

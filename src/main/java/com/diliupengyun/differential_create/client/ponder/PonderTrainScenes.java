package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.mixin.PonderWorldSectionAccessor;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.AnimateParrotInstruction;
import net.createmod.ponder.foundation.instruction.AnimateWorldSectionInstruction;
import net.createmod.ponder.foundation.instruction.PonderInstruction;
import net.minecraft.world.phys.Vec3;

/** A native train storyboard can draw one train as several sections and a rider.
 * Collect each simultaneous move before resolving it: a coupled train has one
 * displacement, whereas lifting/relocating the diagram remains presentation. */
public final class PonderTrainScenes {
    private record Move(ElementLink<WorldSectionElement> link, Vec3 delta, int ticks, boolean presentation) {}
    private record Rider(ElementLink<ParrotElement> link, Vec3 delta, int ticks) {}
    private Batch open, pending;
    private int remaining, moveIndex, elapsed;

    public void move(SceneBuilder builder, String scene, ElementLink<WorldSectionElement> link, Vec3 delta, int ticks) {
        // The native observer lesson reveals the trailer ten ticks later and
        // subtracts the scripted travel from its starting offset. Reveal both
        // together on the short track, so slow acceleration does not leave the
        // incoming cargo visibly floating beyond the illustrated rails.
        if (scene.equals("track_observer")) {
            if (ticks == 0 && (delta.x == 4 || delta.x == 6)) delta = new Vec3(1, delta.y, delta.z);
            if (ticks == 70 && delta.equals(new Vec3(-14, 0, 0))) { delta = new Vec3(-16, 0, 0); ticks = 80; }
        }
        int index = moveIndex++;
        if (open == null) {
            // Independent diagram pieces may enter a few ticks apart (the
            // diagonal train in the signal lesson). Do not serialize those.
            if (pending != null && pending.moves.stream().anyMatch(PonderTrainScenes::physicalMove)) await(builder);
            open = new Batch(); pending = open; elapsed = 0;
        }
        // These three operations lift, turn and lower an illustration of the
        // wrong approach direction. They are not throttle inputs on the track.
        boolean presentation = scene.equals("auto_schedule") && index >= 2 && index <= 4;
        open.moves.add(new Move(link, delta, ticks, presentation));
        remaining = Math.max(remaining, ticks);
    }

    private static boolean physicalMove(Move move) {
        return !move.presentation() && move.ticks() > 0 && move.delta().x != 0
            && move.delta().y == 0 && move.delta().z == 0;
    }

    public boolean joinsObserverTrailer(String scene, int ticks) {
        return scene.equals("track_observer") && ticks == 10 && open != null && open.moves.size() == 2
            && open.moves.getLast().delta().equals(new Vec3(-16, 0, 0));
    }

    public void rider(SceneBuilder builder, ElementLink<ParrotElement> link, Vec3 delta, int ticks) {
        if (open != null) { open.riders.add(new Rider(link, delta, ticks)); return; }
        builder.addInstruction(scene -> PonderSimulation.of(scene.getWorld()).releasePassenger(scene.resolve(link)));
        builder.addInstruction(AnimateParrotInstruction.move(link, delta, ticks));
    }

    public void afterIdle(SceneBuilder builder, int ticks) {
        open = null;
        elapsed += ticks;
        remaining = Math.max(0, remaining - ticks);
        if (remaining == 0) await(builder);
    }

    public void flush(SceneBuilder builder) {
        // Creation instructions for riders and trailing carriages can follow
        // the first move in the same storyboard tick. Resolve the whole batch
        // only after those instructions have registered their visible elements.
        if (open != null && !open.scheduled) {
            builder.addInstruction(open);
            open.scheduled = true;
        }
    }

    public void await(SceneBuilder builder) {
        if (pending == null) return;
        flush(builder);
        Batch movement = pending;
        // The towing boundary travels at 1 m/s. Reserve travel plus acceleration
        // and braking in the native timeline, before testing the actual result.
        int budget = movement.moves.stream().mapToInt(move -> physicalMove(move)
            ? (int) Math.ceil(Math.abs(move.delta().x) * 20) + 60 : move.ticks() + 1).max().orElse(0);
        if (elapsed < budget) builder.idle(budget - elapsed);
        builder.addInstruction(new PonderAwait(movement::arrived, 2400));
        pending = null; open = null; remaining = 0;
    }

    public void beforeHide(SceneBuilder builder) {
        if (pending == null || pending.moves.stream().noneMatch(PonderTrainScenes::physicalMove)) return;
        flush(builder);
        // A departure fades during travel, before the train runs off the short
        // illustrated track. Preserve the native fraction of the journey at
        // which fading starts, scaled to the finite 1 m/s towing boundary plus
        // half a second for acceleration. Waiting for the destination here would
        // leave a fully visible train stopped beyond the end of the rails.
        int travelTicks = elapsed;
        int fadeAt = pending.moves.stream().filter(PonderTrainScenes::physicalMove)
            .mapToInt(move -> (int) Math.ceil(Math.abs(move.delta().x) * 20
                * Math.min(1, (double) travelTicks / move.ticks())) + 10).max().orElse(travelTicks);
        // The observer must see the cargo axle as well as the leading axle.
        // After 5 m of travel the rear axle reaches the detector and the lead
        // is still on the plate. Its native ten-tick stagger was removed above.
        if (new com.simibubi.create.foundation.ponder.CreateSceneBuilder(builder).getScene().getId().getPath().equals("track_observer"))
            fadeAt = 110;
        if (fadeAt > elapsed) builder.idle(fadeAt - elapsed);
        pending = null; open = null; remaining = 0;
    }

    public void finish(SceneBuilder builder) {
        if (pending == null) return;
        flush(builder);
        // Also let a final diagram animation finish when the native scene ends
        // immediately after its last move (the wrench relocation example).
        builder.idle(Math.max(1, remaining));
        await(builder);
    }

    private static final class Batch extends PonderInstruction {
        final List<Move> moves = new ArrayList<>();
        final List<Rider> riders = new ArrayList<>();
        final List<PonderInstruction> presentation = new ArrayList<>();
        final List<WorldSectionElement> driven = new ArrayList<>();
        boolean started, scheduled;

        boolean arrived(PonderScene scene) {
            return started && driven.stream().allMatch(PonderSimulation.of(scene.getWorld())::trainArrived)
                && presentation.stream().allMatch(PonderInstruction::isComplete);
        }
        @Override public boolean isComplete() { return started && presentation.stream().allMatch(PonderInstruction::isComplete); }
        @Override public void reset(PonderScene scene) {
            started = false; presentation.clear(); driven.clear();
        }
        @Override public void tick(PonderScene scene) {
            if (!started) {
                started = true; begin(scene);
                // A zero-duration instruction is already "complete", but its
                // first tick still performs the immediate placement.
                for (var instruction : presentation) instruction.tick(scene);
                return;
            }
            for (var instruction : presentation) if (!instruction.isComplete()) instruction.tick(scene);
        }
        private void begin(PonderScene scene) {
            var simulation = PonderSimulation.of(scene.getWorld());
            // Place the diagram before constructing its physical train. Native
            // signal scenes place and start the same section in one instruction batch.
            for (var move : moves) if (move.ticks() <= 0) {
                var section = scene.resolve(move.link());
                if (section != null) simulation.release(section);
                AnimateWorldSectionInstruction.move(move.link(), move.delta(), 0).tick(scene);
            }
            var physical = new java.util.HashMap<Move, WorldSectionElement>();
            for (var move : moves) {
                if (physical.containsKey(move) || move.presentation() || move.ticks() <= 0
                    || move.delta().x == 0 || move.delta().y != 0 || move.delta().z != 0) continue;
                var support = scene.resolve(move.link());
                if (support == null || !hasBogey(scene, support)) continue;
                var group = new ArrayList<WorldSectionElement>();
                group.add(support);
                for (var candidate : moves) if (!candidate.presentation() && candidate.ticks() == move.ticks()
                    && candidate.delta().equals(move.delta())) {
                    var member = scene.resolve(candidate.link());
                    if (member == null) continue;
                    if (!group.contains(member)) group.add(member);
                    physical.put(candidate, support);
                }
                simulation.driveTrain(scene.getWorld(), group, move.delta().x, move.ticks());
                driven.add(support);
            }
            for (var move : moves) if (move.ticks() > 0 && !physical.containsKey(move)) {
                var section = scene.resolve(move.link());
                if (section != null) simulation.release(section);
                presentation.add(AnimateWorldSectionInstruction.move(move.link(), move.delta(), move.ticks()));
            }
            for (var rider : riders) {
                var passenger = scene.resolve(rider.link());
                simulation.releasePassenger(passenger);
                WorldSectionElement support = null;
                for (var move : moves) if (move.ticks() == rider.ticks() && move.delta().equals(rider.delta())) {
                    support = physical.get(move);
                    if (support != null) break;
                }
                if (passenger != null && support != null) simulation.passenger(passenger, support);
                else presentation.add(AnimateParrotInstruction.move(rider.link(), rider.delta(), rider.ticks()));
            }
        }
        private boolean hasBogey(PonderScene scene, WorldSectionElement section) {
            var selection = ((PonderWorldSectionAccessor) section).differentialcreate$selection();
            if (selection != null) for (var pos : selection)
                if (scene.getWorld().getBlockEntity(pos) instanceof AbstractBogeyBlockEntity) return true;
            return false;
        }
    }
}

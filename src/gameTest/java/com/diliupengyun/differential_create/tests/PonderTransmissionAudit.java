package com.diliupengyun.differential_create.tests;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.diliupengyun.differential_create.kinetics.ISequencerDynamics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.chainDrive.ChainGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;

/** Check the input/output relationships demonstrated by the retained transmission lessons. */
final class PonderTransmissionAudit {
    private record Sample(int tick, double angle) {}
    private final Map<BlockPos, ArrayDeque<Sample>> history = new HashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int elapsed, stable;
    private String state = "";
    private boolean accelerated, reduced, reversed, analog, held, forward, backward;

    void sample(PonderScene scene) {
        if (!scene.getNamespace().equals("create")) return;
        elapsed++;
        switch (scene.getId().getPath()) {
            case "chain_gearshift" -> chain(scene);
            case "rotation_speed_controller" -> controller(scene);
            case "sequenced_gearshift" -> sequencer(scene);
            default -> { }
        }
    }

    private void chain(PonderScene scene) {
        var world = scene.getWorld();
        var first = (ChainGearshiftBlockEntity) world.getBlockEntity(new BlockPos(3, 1, 2));
        var second = (ChainGearshiftBlockEntity) world.getBlockEntity(new BlockPos(1, 1, 4));
        boolean moved = second != null && PonderKinetics.of(world).angle(second, 1) != null;
        double a = first.getModifier(), b = moved ? second.getModifier() : 1;
        settle(a + "/" + b + "/" + moved);
        Double input = average(scene, new BlockPos(3, 4, 2));
        Double middle = average(scene, new BlockPos(2, 3, 2));
        Double output = average(scene, new BlockPos(1, 2, 2));
        if (stable < 45 || input == null || middle == null || output == null) return;
        check(Math.abs(input + 32) < 1, "Chain gearshift changes its input drive: " + input);
        double multiplier = moved ? 1 : a;
        check(Math.abs(middle - input * multiplier) < 1, "Chain row does not follow the input-side ratio");
        check(Math.abs(output - middle / b) < 1, "Chain output does not follow the output-side ratio");
        accelerated |= !moved && a > 1.9 && Math.abs(middle) > Math.abs(input) * 1.9;
        reduced |= moved && b > 1.9 && Math.abs(output) < Math.abs(middle) * .55;
        analog |= moved && b > 1 && b < 1.9 && Math.abs(output) < Math.abs(middle) * .9;
    }

    private void controller(PonderScene scene) {
        var controller = (SpeedControllerBlockEntity) scene.getWorld().getBlockEntity(new BlockPos(1, 1, 1));
        double ratio = controller.targetSpeed.getValue() / 16d;
        var gauge = (KineticBlockEntity) scene.getWorld().getBlockEntity(new BlockPos(1, 2, 3));
        boolean joined = PonderKinetics.of(scene.getWorld()).sameComponent(controller, gauge);
        settle(ratio + "/" + joined);
        Double input = average(scene, controller.getBlockPos());
        Double output = average(scene, new BlockPos(1, 2, 3));
        if (!joined || stable < 30 || input == null || output == null || Math.abs(output) < 1) return;
        // Raising the ratio increases referred inertia: finite torque can briefly
        // pull the input below its target. It must not rewrite or reverse that target.
        check(PonderKinetics.of(scene.getWorld()).commandedSpeed(controller) == 16 && input > 0,
            "Ratio controller rewrites or reverses its input drive");
        check(Math.abs(output * ratio - input) < 1, "Ratio controller output disagrees with its configured ratio");
        accelerated |= Math.abs(output) > Math.abs(input) * 3;
        reversed |= output < 0 && input > 0;
    }

    private void sequencer(PonderScene scene) {
        var world = scene.getWorld();
        var input = (KineticBlockEntity) world.getBlockEntity(new BlockPos(4, 1, 2));
        var output = (KineticBlockEntity) world.getBlockEntity(new BlockPos(2, 1, 2));
        var servo = (ISequencerDynamics) world.getBlockEntity(new BlockPos(3, 1, 2));
        if (elapsed > 50) check(input.getSpeed() < -1, "Sequencer output command stopped or reversed its input");
        forward |= output.getSpeed() > 1;
        backward |= output.getSpeed() < -1;
        held |= forward && backward && servo.differentialcreate$motion().settled() && Math.abs(output.getSpeed()) < .1;
    }

    /** Average actual rendered travel over a second; physical torsional oscillations are allowed. */
    private Double average(PonderScene scene, BlockPos pos) {
        if (!(scene.getWorld().getBlockEntity(pos) instanceof KineticBlockEntity be)) return null;
        Double angle = PonderKinetics.of(scene.getWorld()).angle(be, 1);
        if (angle == null) { history.remove(pos); return null; }
        var samples = history.computeIfAbsent(pos, ignored -> new ArrayDeque<>());
        samples.addLast(new Sample(elapsed, angle));
        while (samples.size() > 21) samples.removeFirst();
        if (samples.size() < 21) return null;
        return (angle - samples.getFirst().angle()) / 360 * 1200 / (elapsed - samples.getFirst().tick());
    }

    private void settle(String next) {
        stable = state.equals(next) ? stable + 1 : 0;
        state = next;
    }

    private void check(boolean condition, String message) {
        if (!condition && failures.size() < 8) failures.add("tick " + elapsed + ": " + message);
    }

    void verify(PonderScene scene) {
        if (!scene.getNamespace().equals("create")) return;
        switch (scene.getId().getPath()) {
            case "chain_gearshift" -> check(accelerated && reduced && analog, "Missing chain speed-up, reduction or analog adjustment");
            case "rotation_speed_controller" -> check(accelerated && reversed, "Missing controller speed-up or reversal after Next unlocks");
            case "sequenced_gearshift" -> check(forward && backward && held, "Sequencer must move both ways and hold, with its input still running");
            default -> { }
        }
        if (!failures.isEmpty()) throw new AssertionError("Transmission in " + scene.getId() + ": " + failures);
    }
}

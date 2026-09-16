package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real block fixtures for continuous, finite-stiffness engagement and structural failure. */
final class ElasticKineticsTests {
    private ElasticKineticsTests() {}
    static KineticBlockEntity flywheel(GameTestHelper h, int x, int z) {
        BlockPos pos = new BlockPos(x, 3, z);
        h.setBlock(pos, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        KineticBlockEntity be = h.getBlockEntity(pos);
        NetworkTopology.mark(be);
        return be;
    }
    static void shaft(GameTestHelper h, int x, int z) {
        h.setBlock(new BlockPos(x, 3, z), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        NetworkTopology.mark((KineticBlockEntity) h.getBlockEntity(new BlockPos(x, 3, z)));
    }
    static void speed(KineticBlockEntity be, double rpm) { ElasticKinetics.state(be).physicalSpeed = rpm; }
    static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
        h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
            label + ": expected " + expected + ", got " + actual);
    }
    static void phaseAlignment(GameTestHelper h) {
        var wheel = flywheel(h, 6, 3);
        var left = flywheel(h, 2, 11); var right = flywheel(h, 4, 11);
        var input = flywheel(h, 2, 19); var output = flywheel(h, 6, 19);
        shaft(h, 3, 19); shaft(h, 5, 19);
        BlockPos clutchPos = new BlockPos(4, 3, 19);
        h.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        ClutchBlockEntity clutch = h.getBlockEntity(clutchPos);
        clutch.source = h.absolutePos(clutchPos.west());
        NetworkTopology.mark(clutch);
        h.runAfterDelay(5, () -> {
            speed(wheel, 16); ElasticKinetics.state(wheel).angle = 17.1375;
            shaft(h, 5, 3); NetworkTopology.flush();
            KineticBlockEntity newShaft = h.getBlockEntity(new BlockPos(5, 3, 3));
            near(h, 17.1375, ElasticKinetics.state(wheel).angle, 1e-12, "adding an earlier-positioned shaft retains the existing wheel angle");
            near(h, ElasticKinetics.state(wheel).angle, ElasticKinetics.state(newShaft).angle, 1e-12, "new shaft is immediately phase-aligned");
            near(h, 16, wheel.getSpeed(), 0, "phase alignment cannot transfer physical momentum");

            speed(left, 10); speed(right, 8);
            ElasticKinetics.state(left).angle = .1; ElasticKinetics.state(right).angle = 42.875;
            shaft(h, 3, 11); NetworkTopology.flush();
            near(h, ElasticKinetics.state(left).angle, ElasticKinetics.state(right).angle, 1e-12, "joined rotor histories share an assembly zero");
            near(h, 10, left.getSpeed(), 0, "joining preserves driver speed");
            near(h, 8, right.getSpeed(), 0, "joining preserves driven speed");
            var contacts = ((INetworkDynamics) left.getOrCreateNetwork()).differentialcreate$data().connections;
            var contact = contacts.getFirst();
            contact.twist = .00005; contact.save();
            NetworkTopology.mark(left); NetworkTopology.flush();
            assertAlignedContacts(h, left);
            var rebuilt = ((INetworkDynamics) left.getOrCreateNetwork()).differentialcreate$data().connections;
            h.assertTrue(rebuilt.stream().anyMatch(edge -> Math.abs(edge.twist - .00005) < 1e-12), "rephasing preserves saved spring preload");

            ElasticKinetics.state(input).angle = 1.125; ElasticKinetics.state(output).angle = -4.375;
            NetworkTopology.mark(clutch); NetworkTopology.flush();
            near(h, 1.125, ElasticKinetics.state(input).angle, 1e-12, "input half preserves its phase");
            near(h, -4.375, ElasticKinetics.state(output).angle, 1e-12, "friction contact cannot align the output to the input");
            assertAlignedContacts(h, input);
        });
        h.runAfterDelay(10, () -> {
            assertAlignedContacts(h, wheel); assertAlignedContacts(h, left);
            shaft(h, 4, 3); NetworkTopology.flush();
            assertAlignedContacts(h, wheel);
            var tag = wheel.saveWithoutMetadata(h.getLevel().registryAccess());
            near(h, ElasticKinetics.state(wheel).angle, tag.getDouble("DCShaftAngle"), 0, "aligned phase is saved for reload");
            h.succeed();
        });
    }
    private static void assertAlignedContacts(GameTestHelper h, KineticBlockEntity member) {
        for (var edge : ((INetworkDynamics) member.getOrCreateNetwork()).differentialcreate$data().connections)
            if (edge.clutch == null)
                near(h, edge.twist, edge.ratio * ElasticKinetics.state(edge.a).angle - ElasticKinetics.state(edge.b).angle,
                    1e-9, "shaft/gear phase differs only by its elastic twist");
    }
    static void collision(GameTestHelper h) {
        var fast = flywheel(h, 2, 3); var stopped = flywheel(h, 4, 3);
        var gentleA = flywheel(h, 2, 9); var gentleB = flywheel(h, 4, 9);
        h.runAfterDelay(5, () -> {
            speed(fast, 16); speed(stopped, 0); speed(gentleA, 10); speed(gentleB, 8);
            shaft(h, 3, 3); shaft(h, 3, 9); NetworkTopology.flush();
            near(h, 16, fast.getSpeed(), 0, "topology alone transfers no impulse");
            near(h, 0, stopped.getSpeed(), 0, "new rotor starts at rest");
            var trial = ElasticKinetics.sample(MachiningClock.networks(h.getLevel()), RotationMath.TICK_MINUTES);
            h.assertTrue(trial.result.broken() >= 0 && trial.result.duration() < RotationMath.TICK_MINUTES,
                "strength crossing occurs inside the tick: broken=" + trial.result.broken() + ", duration=" + trial.result.duration()
                + ", links=" + trial.connections.stream().filter(e -> e.a == fast || e.b == fast || e.a == stopped || e.b == stopped)
                    .map(e -> e.a.getBlockPos() + "->" + e.b.getBlockPos() + " q=" + e.ratio + " twist=" + e.twist).toList()
                );
            int a = trial.members.indexOf(fast), b = trial.members.indexOf(stopped);
            h.assertTrue(trial.result.speed()[a] < 16 && trial.result.speed()[b] > 0,
                "some momentum is exchanged before the shaft fails");
        });
        h.runAfterDelay(8, () -> {
            h.assertTrue(h.getBlockState(new BlockPos(3, 3, 3)).isAir(), "overloaded bridge shaft breaks");
            h.assertFalse(fast.isRemoved() || stopped.isRemoved(), "stronger flywheels survive");
            near(h, 16, fast.getSpeed() + stopped.getSpeed(), 1e-4, "fracture preserves flywheel momentum");
            h.assertFalse(h.getBlockState(new BlockPos(3, 3, 9)).isAir(), "gentle connection survives");
        });
        h.runAfterDelay(110, () -> {
            near(h, 9, gentleA.getSpeed(), .002, "gentle driver settles");
            near(h, 9, gentleB.getSpeed(), .002, "gentle driven rotor settles");
            var owner = (KineticBlockEntity) h.getBlockEntity(new BlockPos(3, 3, 9));
            var tag = owner.saveWithoutMetadata(h.getLevel().registryAccess());
            h.assertTrue(tag.contains("DCLocalSpeed") && tag.contains("DCElasticContacts"), "rotor and contact state are persisted");
            var clone = (KineticBlockEntity) owner.getType().create(owner.getBlockPos(), owner.getBlockState());
            clone.setLevel(h.getLevel()); clone.loadWithComponents(tag, h.getLevel().registryAccess());
            near(h, ElasticKinetics.state(owner).physicalSpeed, ElasticKinetics.state(clone).physicalSpeed, 0, "local speed NBT precision");
            h.assertTrue(ElasticKinetics.state(owner).elasticConnections.equals(ElasticKinetics.state(clone).elasticConnections),
                "spring preload survives NBT");
            h.succeed();
        });
    }
    static void lockedClutchImpact(GameTestHelper h) {
        var a = flywheel(h, 2, 3);
        shaft(h, 3, 3); shaft(h, 5, 3);
        BlockPos clutchPos = new BlockPos(4, 3, 3);
        h.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        ClutchBlockEntity clutch = h.getBlockEntity(clutchPos);
        clutch.source = h.absolutePos(clutchPos.west());
        NetworkTopology.mark(clutch);
        KineticBlockEntity[] b = {null};
        h.runAfterDelay(5, () -> ((INetworkDynamics) a.getOrCreateNetwork()).differentialcreate$setSpeed(16));
        h.runAfterDelay(50, () -> {
            h.assertTrue(((IClutchDynamics) clutch).differentialcreate$isLocked(), "unloaded clutch has synchronized");
            b[0] = flywheel(h, 6, 3); NetworkTopology.flush();
            near(h, 0, b[0].getSpeed(), 0, "remote flywheel begins at rest");
        });
        h.runAfterDelay(55, () -> {
            h.assertFalse(clutch.isRemoved() || a.isRemoved() || b[0].isRemoved(), "clutch limits the remote engagement shock");
            h.assertFalse(((IClutchDynamics) clutch).differentialcreate$isLocked(), "friction surface starts slipping");
            h.assertTrue(b[0].getSpeed() > 0 && b[0].getSpeed() < a.getSpeed(), "finite friction gradually accelerates the load");
            h.assertTrue(Math.abs(((IClutchDynamics) clutch).differentialcreate$getLastCouplingTorque()) <= 32.001, "clutch torque cap");
        });
        h.runAfterDelay(280, () -> {
            near(h, 8, a.getSpeed(), .005, "driver converges without an instantaneous merge");
            near(h, 8, b[0].getSpeed(), .005, "driven flywheel converges");
            h.assertTrue(((IClutchDynamics) clutch).differentialcreate$isLocked(), "clutch relocks for display after synchronization");
            h.succeed();
        });
    }
    static void strength(GameTestHelper h) {
        // Both shafts start at exactly zero: the failure is driven by sustained opposed motor torques.
        BlockPos left = new BlockPos(2, 3, 3), right = new BlockPos(4, 3, 3);
        h.setBlock(left, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(right, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.WEST));
        CreativeMotorBlockEntity a = h.getBlockEntity(left), b = h.getBlockEntity(right);
        a.generatedSpeed.setValue(16); b.generatedSpeed.setValue(16);
        shaft(h, 3, 3); NetworkTopology.flush();
        speed(a, 0); speed(b, 0);
        var leftFly = flywheel(h, 2, 12); var rightFly = flywheel(h, 6, 12);
        BlockPos clutchPos = new BlockPos(4, 3, 12);
        h.setBlock(clutchPos, AllBlocks.CLUTCH.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        ClutchBlockEntity clutch = h.getBlockEntity(clutchPos);
        clutch.source = h.absolutePos(clutchPos.west());
        for (int x : new int[]{3, 5}) for (int z : new int[]{12, 13})
            h.setBlock(new BlockPos(x, 3, z), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        shaft(h, 4, 13); NetworkTopology.flush(); speed(leftFly, 16); speed(rightFly, 0);
        h.runAfterDelay(5, () -> {
            h.assertTrue(h.getBlockState(new BlockPos(3, 3, 3)).isAir(), "continuous overload breaks a shaft without an initial speed mismatch");
            h.assertFalse(a.isRemoved() || b.isRemoved(), "failure is assigned to the weaker shaft");
            boolean bypassBroken = h.getBlockState(new BlockPos(4, 3, 13)).isAir();
            for (int x : new int[]{3, 5}) for (int z : new int[]{12, 13})
                bypassBroken |= h.getBlockState(new BlockPos(x, 3, z)).isAir();
            h.assertTrue(bypassBroken, "a parallel rigid path receives no blanket clutch protection");
            h.assertFalse(leftFly.isRemoved() || rightFly.isRemoved(), "bypass overload preserves the stronger flywheels");
            h.succeed();
        });
    }
    static void gearedConservation(GameTestHelper h) {
        BlockPos controllerPos = new BlockPos(10, 3, 5);
        h.setBlock(controllerPos, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
        SpeedControllerBlockEntity controller = h.getBlockEntity(controllerPos);
        controller.targetSpeed.setValue(256);
        h.setBlock(controllerPos.above(), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        BlockPos inputPos = controllerPos.above().north();
        h.setBlock(inputPos, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        KineticBlockEntity input = h.getBlockEntity(inputPos);
        var output = flywheel(h, 12, 5); shaft(h, 11, 5);
        h.runAfterDelay(5, () -> {
            NetworkTopology.flush();
            speed(input, .25); speed(output, 0);
            var network = input.getOrCreateNetwork();
            var ratios = ((INetworkDynamics) network).differentialcreate$getRatios();
            double q = ratios.get(output) / ratios.get(input);
            near(h, 16, Math.abs(q), 1e-6, "actual controller speed ratio");
            var trial = ElasticKinetics.sample(MachiningClock.networks(h.getLevel()), 80 * RotationMath.TICK_MINUTES);
            h.assertTrue(trial.result.broken() < 0, "small geared engagement stays below strength");
            int a = trial.members.indexOf(input), b = trial.members.indexOf(output);
            double wa = trial.result.speed()[a], wb = trial.result.speed()[b];
            near(h, .125, .5 * wa + q * .5 * wb, 1e-7, "generalized momentum through a real 16x controller");
            h.assertTrue(Math.abs(wa - .25) > .01 && wb * q > 0, "finite geared contact transfers momentum to the driven flywheel");
            h.assertTrue(.25 * (wa * wa + wb * wb) <= .25 * .25 * .25 + 1e-8, "passive engagement does not create kinetic energy");
            h.succeed();
        });
    }
}

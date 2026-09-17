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
    static void masslessSpeedReadout(GameTestHelper h) {
        var wheel = flywheel(h, 2, 3);
        shaft(h, 3, 3);
        KineticBlockEntity shaft = h.getBlockEntity(new BlockPos(3, 3, 3));
        BlockPos cogPos = new BlockPos(4, 3, 3);
        h.setBlock(cogPos, AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        KineticBlockEntity cog = h.getBlockEntity(cogPos);
        NetworkTopology.mark(cog);
        h.runAfterDelay(5, () -> {
            NetworkTopology.flush();
            near(h, 0, InertiaRegistry.getInertia(shaft), 0, "shaft is genuinely massless");
            near(h, 0, InertiaRegistry.getInertia(cog), 0, "large cog is genuinely massless");
            speed(wheel, 1024); speed(shaft, 1024); speed(cog, 1014.784);
            var connections = ((INetworkDynamics) wheel.getOrCreateNetwork()).differentialcreate$data().connections;
            var contact = connections.stream().filter(edge -> (edge.a == shaft && edge.b == cog)
                || (edge.a == cog && edge.b == shaft)).findFirst().orElseThrow();
            // Existing saves may contain both a massless constraint residual and a biased speed.
            contact.twist = 6e-5; contact.save();
        });
        for (int tick = 6; tick < 20; tick++) h.runAfterDelay(tick, () -> {
            near(h, 1024, NetworkDynamics.preciseSpeed(wheel), 1e-8, "unloaded flywheel keeps its momentum");
            near(h, wheel.getSpeed(), shaft.getSpeed(), 1e-6, "shaft readout follows the endpoint speed");
            near(h, shaft.getSpeed(), cog.getSpeed(), 1e-6, "coaxial large cog cannot retain a steady 9 RPM sampling offset");
        });
        h.runAfterDelay(12, () -> { NetworkTopology.mark(cog); NetworkTopology.flush(); });
        h.runAfterDelay(20, h::succeed);
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
        double[] slipRange = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
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
        for (int tick = 90; tick < 110; tick++) h.runAfterDelay(tick, () -> {
            double a = NetworkDynamics.preciseSpeed(gentleA), b = NetworkDynamics.preciseSpeed(gentleB);
            near(h, 18, a + b, 1e-8, "undamped engagement conserves momentum");
            var network = gentleA.getOrCreateNetwork();
            double elastic = ((INetworkDynamics) network).differentialcreate$data().connections.stream()
                .mapToDouble(edge -> .5 * edge.stiffness() * edge.twist * edge.twist).sum();
            near(h, 41, ElasticKinetics.kineticEnergy(network) + elastic, 1e-7,
                "undamped engagement conserves kinetic plus elastic energy");
            slipRange[0] = Math.min(slipRange[0], a - b);
            slipRange[1] = Math.max(slipRange[1], a - b);
        });
        h.runAfterDelay(110, () -> {
            h.assertTrue(slipRange[0] < -1 && slipRange[1] > 1,
                "gentle engagement keeps oscillating in both directions without decaying");
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
    static void repeatedShaftFracture(GameTestHelper h) {
        var left = flywheel(h, 2, 3); var right = flywheel(h, 6, 3);
        shaft(h, 3, 3); shaft(h, 4, 3);
        h.runAfterDelay(5, () -> {
            // Recreate the saved crash state, then let the normal server tick locate
            // the fracture, destroy the shaft and rebuild the remaining networks.
            speed(left, 165.86302232741826); speed(right, 197.51583239706156);
            for (int x : new int[]{3, 4}) speed(h.getBlockEntity(new BlockPos(x, 3, 3)), 165.86302232741826);
            shaft(h, 5, 3); NetworkTopology.flush();
            var contacts = ((INetworkDynamics) left.getOrCreateNetwork()).differentialcreate$data().connections;
            for (var edge : contacts) {
                boolean touchesLeft = edge.a == left || edge.b == left;
                edge.twist = touchesLeft ? (edge.a == left ? -1 : 1) * .0007733898555839802 : 0;
                edge.save();
            }
        });
        h.runAfterDelay(8, () -> {
            assertBrokenBridge(h, left, right);
            near(h, 165.86302232741826 + 197.51583239706156,
                NetworkDynamics.preciseSpeed(left) + NetworkDynamics.preciseSpeed(right), 1e-8,
                "saved-state fracture preserves flywheel momentum");
            // Enough initial speed difference to keep all forty replacements overloaded.
            speed(left, 8000); speed(right, 0);
            replaceBridge(h);
        });
        for (int attempt = 0; attempt < 40; attempt++) {
            boolean last = attempt == 39;
            h.runAfterDelay(10 + 2 * attempt, () -> {
                assertBrokenBridge(h, left, right);
                near(h, 8000, NetworkDynamics.preciseSpeed(left) + NetworkDynamics.preciseSpeed(right), 1e-7,
                    "repeated replacement/fracture preserves rotor momentum");
                if (last) h.succeed(); else replaceBridge(h);
            });
        }
    }
    private static void assertBrokenBridge(GameTestHelper h, KineticBlockEntity left, KineticBlockEntity right) {
        h.assertFalse(left.isRemoved() || right.isRemoved(), "stronger flywheels survive repeated shaft overload");
        boolean broken = false;
        for (int x = 3; x <= 5; x++) broken |= h.getBlockState(new BlockPos(x, 3, 3)).isAir();
        h.assertTrue(broken, "each overloaded replacement fractures a bridge shaft");
    }
    private static void replaceBridge(GameTestHelper h) {
        for (int x = 3; x <= 5; x++) if (h.getBlockState(new BlockPos(x, 3, 3)).isAir()) shaft(h, x, 3);
        NetworkTopology.flush();
    }
    static void poweredShaftReplacement(GameTestHelper h) {
        var left = flywheel(h, 2, 11); var middle = flywheel(h, 6, 11); var fast = flywheel(h, 9, 11);
        shaft(h, 3, 11); shaft(h, 8, 11); shaft(h, 11, 11);
        BlockPos clutchPos = new BlockPos(10, 3, 11), controllerPos = new BlockPos(12, 3, 11);
        h.setBlock(clutchPos, com.diliupengyun.differential_create.content.ModContent.BRASS_CLUTCH.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        ClutchBlockEntity clutch = h.getBlockEntity(clutchPos);
        clutch.source = h.absolutePos(clutchPos.west());
        h.setBlock(controllerPos, AllBlocks.ROTATION_SPEED_CONTROLLER.getDefaultState()
            .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
        SpeedControllerBlockEntity controller = h.getBlockEntity(controllerPos);
        controller.targetSpeed.setValue(256);
        h.setBlock(controllerPos.above(), AllBlocks.LARGE_COGWHEEL.getDefaultState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        BlockPos motorPos = controllerPos.above().north();
        h.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        CreativeMotorBlockEntity motor = h.getBlockEntity(motorPos);
        // Seed the running assembly before enabling its drive; a cold 16x startup
        // has a separate overload and can break the fixture during its setup ticks.
        motor.generatedSpeed.setValue(0);
        var placed = new java.util.ArrayList<KineticBlockEntity>();
        h.runAfterDelay(5, () -> {
            motor.generatedSpeed.setValue(256); NetworkTopology.mark(motor);
            NetworkTopology.flush();
            speed(left, 167.56082907714512); speed(middle, 195.8180256473347);
            speed(h.getBlockEntity(new BlockPos(3, 3, 11)), 167.56082907714512);
            for (int x = 8; x <= 12; x++) speed(h.getBlockEntity(new BlockPos(x, 3, 11)), 8044.6580292513545);
            speed(motor, 502.79108222926703); speed(h.getBlockEntity(controllerPos.above()), 502.79111169334266);
            for (var wheel : new KineticBlockEntity[]{left, fast}) {
                var contacts = ((INetworkDynamics) wheel.getOrCreateNetwork()).differentialcreate$data().connections;
                for (var edge : contacts) {
                    int ax = h.relativePos(edge.a.getBlockPos()).getX(), bx = h.relativePos(edge.b.getBlockPos()).getX();
                    if (ax == 2 && bx == 3) edge.twist = -.0007163709952172117;
                    else if (ax == 8 && bx == 9) edge.twist = -.0008191569783805716;
                    else continue;
                    edge.save();
                }
            }
        });
        int[] candidates = {4, 5, 7, 3, 8, 11};
        for (int attempt = 0; attempt < 200; attempt++) {
            int start = attempt % candidates.length;
            h.runAfterDelay(6 + 4 * attempt, () -> {
                for (var rotor : new KineticBlockEntity[]{left, middle, fast, motor}) {
                    h.assertFalse(rotor.isRemoved(), "loaded replacements preserve stronger rotors");
                    h.assertTrue(Double.isFinite(NetworkDynamics.preciseSpeed(rotor)), "loaded network continues with finite speed");
                }
                for (int offset = 0; offset < candidates.length; offset++) {
                    int x = candidates[(start + offset) % candidates.length];
                    BlockPos pos = new BlockPos(x, 3, 11);
                    if (!h.getBlockState(pos).isAir()) continue;
                    shaft(h, x, 11); placed.add(h.getBlockEntity(pos)); NetworkTopology.flush();
                    break;
                }
            });
        }
        h.runAfterDelay(810, () -> {
            h.assertTrue(placed.stream().filter(KineticBlockEntity::isRemoved).count() >= 20,
                "sustained motor-driven replacements actually exercise repeated fracture and network rebuilding");
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
            near(h, 16, NetworkDynamics.preciseSpeed(a) + NetworkDynamics.preciseSpeed(b[0]), 1e-8,
                "frictional engagement conserves flywheel momentum");
            var network = a.getOrCreateNetwork();
            var contacts = ((INetworkDynamics) network).differentialcreate$data().connections;
            double elastic = contacts.stream().mapToDouble(edge -> .5 * edge.stiffness() * edge.twist * edge.twist).sum();
            double residualBound = contacts.stream().mapToDouble(edge -> 2 * Math.pow(PhysicsTuning.CLUTCH_TORQUE, 2) / edge.stiffness()).sum();
            h.assertTrue(ElasticKinetics.kineticEnergy(network) + elastic <= 32 + residualBound,
                "sliding removes the initial mismatch energy while allowing bounded residual elastic oscillation");
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

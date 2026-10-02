package com.diliupengyun.differential_create.tests;

import java.util.Arrays;
import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Real moving tools exercise snapshot isolation, refreshed contacts, and event-search preparation cost. */
final class BoundarySnapshotTests {
    private static final BlockPos POS = new BlockPos(6, 8, 12);
    private static final double DT = RotationMath.TICK_MINUTES;
    private BoundarySnapshotTests() {}
    static void capture(GameTestHelper h) {
        h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int y = 0; y <= 7; y++) h.setBlock(POS.east().above(y), Blocks.SLIME_BLOCK);
        for (int z = -4; z <= 4; z++) {
            h.setBlock(POS.east().above(7).offset(0, 0, z), Blocks.SLIME_BLOCK);
            h.setBlock(POS.east().above(8).offset(0, 0, z), AllBlocks.MECHANICAL_DRILL.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        }
        h.runAfterDelay(5, () -> {
            MechanicalBearingBlockEntity be = h.getBlockEntity(POS);
            be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
            var root = be.getMovedContraption();
            var networks = List.of(be.getOrCreateNetwork());
            ElasticKinetics.state(be).physicalSpeed = 128;
            root.tickActors(); ContraptionMotion.begin(h.getLevel());
            try {
                h.assertTrue(root.getContraption().getActors().size() == 9, "all nine moving drills assembled");
                var context = root.getContraption().getActors().getFirst().getRight();
                var work = ContraptionActorWork.of(context);
                work.differentialcreate$remainingTravel();
                var drive = ContraptionPhysics.drive(context);
                var path = ContraptionMotion.path(drive, context);
                h.assertTrue(path != null, "drill has a physical contact path");
                double travel = ContraptionMotion.travel(drive);
                for (double signed : new double[]{-16, -1, 0, 1, 16}) {
                    double absolute = Math.abs(signed) + 3;
                    double expected = signed == 0 ? Double.POSITIVE_INFINITY
                        : Math.min(ContraptionMotion.allowance(drive, Math.signum(signed)), path.exit(travel, Math.signum(signed)))
                            * absolute / Math.abs(signed);
                    h.assertTrue(expected == work.differentialcreate$phaseLimit(absolute, signed, 1000),
                        "cached geometry preserves both directions and reversal travel");
                }
                var limits = work.differentialcreate$boundary(work.differentialcreate$remainingTravel());
                h.assertTrue(Double.isInfinite(limits.travel())
                    && limits.positive() == Math.min(ContraptionMotion.allowance(drive, 1), path.exit(travel, 1))
                    && limits.negative() == Math.min(ContraptionMotion.allowance(drive, -1), path.exit(travel, -1)),
                    "idle tools expose both signed contact boundaries independently of work travel");
                var snapshot = ElasticKinetics.snapshot(networks);
                double angle = ElasticKinetics.state(be).angle;
                double progress = context.data.getDouble("DCActorTravel");
                var expected = snapshot.sample(DT / 8).result;
                for (double duration : new double[]{DT / 8, DT / 128, DT, 0, DT / 4096, DT / 8}) {
                    same(h, ElasticKinetics.sample(networks, duration).result, snapshot.sample(duration).result);
                    var full = snapshot.sample(duration);
                    var motionOnly = snapshot.motion(duration);
                    h.assertTrue(full.travel(be) == motionOnly.travel(be) && full.signedTravel(be) == motionOnly.signedTravel(be),
                        "displacement-only probes preserve signed and absolute tool travel");
                }
                h.assertTrue(ElasticKinetics.state(be).angle == angle && NetworkDynamics.preciseSpeed(be) == 128
                    && context.data.getDouble("DCActorTravel") == progress, "discarded trials grant no rotor motion or tool work");
                ElasticKinetics.state(be).physicalSpeed = 0;
                same(h, expected, snapshot.sample(DT / 8).result);
                var stopped = ElasticKinetics.snapshot(networks).sample(DT / 8);
                h.assertTrue(stopped.result.speed()[0] == 0 && stopped.travel(be) == 0, "next capture observes a stopped rotor");
                ElasticKinetics.state(be).physicalSpeed = 128;

                var behaviour = MovementBehaviour.REGISTRY.get(context.state);
                BlockPos target = BlockPos.containing(root.toGlobalVector(Vec3.atCenterOf(context.localPos)
                    .add(behaviour.getActiveAreaOffset(context)), 1));
                h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                same(h, expected, snapshot.sample(DT / 8).result);
                var loaded = ElasticKinetics.snapshot(networks).sample(DT / 8);
                h.assertTrue(context.data.contains("BreakingPos") && loaded.result.speed()[0] < expected.speed()[0],
                    "next event capture acquires a newly placed cutting load");
                h.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                var fresh = ElasticKinetics.snapshot(networks);
                var motion = fresh.sample(DT / 8);
                motion.commit(); ContraptionMotion.advance(motion);
                work.differentialcreate$remainingTravel();
                var updatedPath = ContraptionMotion.path(drive, context);
                double updatedTravel = ContraptionMotion.travel(drive);
                for (int sign : new int[]{-1, 1}) {
                    double expectedLimit = Math.min(ContraptionMotion.allowance(drive, sign), updatedPath.exit(updatedTravel, sign));
                    h.assertTrue(expectedLimit == work.differentialcreate$phaseLimit(1, sign, 1000),
                        "next event refreshes geometry after committed travel");
                }
                same(h, motion.result, fresh.sample(DT / 8).result);
                var advanced = ElasticKinetics.snapshot(networks).sample(DT / 8);
                h.assertTrue(!Arrays.equals(motion.result.angle(), advanced.result.angle()), "next capture uses the committed rotor state");
                ElasticKinetics.state(be).physicalSpeed = 128;
                var tools = root.getContraption().getActors().stream().map(pair -> ContraptionActorWork.of(pair.getRight())).toList();
                double boundary = search(networks, be, tools, true);
                h.assertTrue(boundary > 0 && boundary < DT / 8, "benchmark locates a real moving drill's voxel boundary");
                h.assertTrue(boundary == search(networks, be, tools, false), "reused inputs locate exactly the same contact boundary");
                double accelerated = acceleratedSearch(networks, be, tools);
                h.assertTrue(Math.abs(accelerated - boundary) <= 1e-12,
                    "production event limits locate the same monotone contact as the analytical rotor trajectory");
                benchmark(networks, be, tools);
            } finally { ContraptionMotion.end(h.getLevel()); }
            root.setAngle(0); be.disassemble(); h.succeed();
        });
    }
    private static void same(GameTestHelper h, TorsionSolver.Result expected, TorsionSolver.Result actual) {
        h.assertTrue(expected.duration() == actual.duration() && expected.broken() == actual.broken()
            && Arrays.equals(expected.speed(), actual.speed()) && Arrays.equals(expected.twist(), actual.twist())
            && Arrays.equals(expected.angle(), actual.angle()) && Arrays.equals(expected.travel(), actual.travel())
            && Arrays.equals(expected.torque(), actual.torque()) && Arrays.equals(expected.slipping(), actual.slipping()),
            "fixed inputs produce exactly the same duration, speeds, travel, contacts, and fracture");
    }
    private static double search(List<KineticNetwork> networks, MechanicalBearingBlockEntity be, List<ContraptionActorWork> tools, boolean reuse) {
        double[] budgets = tools.stream().mapToDouble(ContraptionActorWork::differentialcreate$remainingTravel).toArray();
        var snapshot = reuse ? ElasticKinetics.snapshot(networks) : null;
        return ShaftClock.firstBoundary(DT / 8, minutes -> {
            var trial = reuse ? snapshot.sample(minutes) : ElasticKinetics.sample(networks, minutes);
            double travel = trial.travel(be), signed = trial.signedTravel(be);
            for (int i = 0; i < tools.size(); i++)
                if (travel >= tools.get(i).differentialcreate$phaseLimit(travel, signed, budgets[i])) return true;
            return false;
        });
    }
    private static double acceleratedSearch(List<KineticNetwork> networks, MechanicalBearingBlockEntity be, List<ContraptionActorWork> tools) {
        var limits = tools.stream().map(tool -> {
            double budget = tool.differentialcreate$remainingTravel();
            return new ElasticKinetics.WorkEvent(be, tool.differentialcreate$boundary(budget));
        }).toList();
        var snapshot = ElasticKinetics.snapshot(networks);
        return snapshot.advanceToEvent(DT / 8, limits).result.duration();
    }
    private static void benchmark(List<KineticNetwork> networks, MechanicalBearingBlockEntity be, List<ContraptionActorWork> tools) {
        for (int i = 0; i < 16; i++) { search(networks, be, tools, true); acceleratedSearch(networks, be, tools); }
        double[] legacy = new double[7], production = new double[7];
        for (int round = 0; round < legacy.length; round++) for (int pass = 0; pass < 2; pass++) {
            boolean eventLimits = (round + pass) % 2 == 0;
            long start = System.nanoTime();
            for (int i = 0; i < 4; i++) {
                if (eventLimits) acceleratedSearch(networks, be, tools);
                else search(networks, be, tools, true);
            }
            (eventLimits ? production : legacy)[round] = (System.nanoTime() - start) / 4e6;
        }
        Arrays.sort(legacy); Arrays.sort(production);
        System.out.printf(java.util.Locale.ROOT,
            "Boundary search benchmark: 128 RPM, %d moving drills, legacy=%.3f ms/search, production=%.3f ms/search, speedup=%.2fx%n",
            tools.size(), legacy[3], production[3], legacy[3] / production[3]);
    }
}

package com.diliupengyun.differential_create.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Opt-in measurements of native server-tick motion, without manually advancing the solver.
 * Run with -PgravitySelect=measure.*. Raw positions use metres (one block width = one metre). */
final class GravityAccelerationMeasurements {
    private static final int TICKS = 120;
    private record Case(String name, String kind, int blocks, String shape, boolean flywheel) {}
    static Collection<TestFunction> cases(String selected) {
        // Keep measurements out of ordinary regression runs and the release artifact.
        if (!selected.contains("measure")) return List.of();
        var result = new ArrayList<TestFunction>();
        var cases = List.of(
            new Case("measure_pulley_2", "pulley", 2, "flat", false),
            new Case("measure_pulley_16_flat", "pulley", 16, "flat", false),
            new Case("measure_pulley_16_column", "pulley", 16, "column", false),
            new Case("measure_pulley_64", "pulley", 64, "cube", false),
            new Case("measure_pulley_2_flywheel", "pulley", 2, "flat", true),
            new Case("measure_pulley_64_flywheel", "pulley", 64, "cube", true),
            new Case("measure_gantry_3", "gantry", 2, "flat", false),
            new Case("measure_gantry_17", "gantry", 16, "flat", false),
            new Case("measure_gantry_65", "gantry", 64, "cube", false),
            new Case("measure_gantry_65_flywheel", "gantry", 64, "cube", true),
            new Case("measure_gantry_horizontal", "gantry_horizontal", 2, "flat", false),
            new Case("measure_bearing_3", "bearing", 3, "eccentric", false),
            new Case("measure_bearing_3_flywheel", "bearing", 3, "eccentric", true),
            new Case("measure_bearing_balanced", "bearing", 5, "balanced", false),
            new Case("measure_bearing_vertical", "bearing_vertical", 3, "eccentric", false));
        for (var c : cases) if (java.util.Arrays.stream(selected.split(",")).anyMatch(c.name::matches))
            result.add(new TestFunction(c.name, c.name, "differentialcreate_gravity:kineticphysicstests.physics_test",
                TICKS + 40, 0, true, h -> setup(h, c)));
        return result;
    }
    private static void setup(GameTestHelper h, Case c) {
        KineticPhysicsTests.forceImpactChunks(h);
        var pos = new BlockPos(12, 80, 12);
        boolean bearing = c.kind.startsWith("bearing"), gantry = c.kind.startsWith("gantry");
        boolean horizontal = c.kind.equals("gantry_horizontal"), vertical = c.kind.equals("bearing_vertical");
        if (gantry) {
            for (int n = 8; n <= 84; n++) {
                var p = horizontal ? new BlockPos(12, 80, n) : new BlockPos(12, n, 12);
                // A stationary horizontal control needs only the nearby rail.
                if (horizontal && n > 24) break;
                h.setBlock(p, AllBlocks.GANTRY_SHAFT.getDefaultState().setValue(GantryShaftBlock.FACING,
                    horizontal ? Direction.SOUTH : Direction.UP));
            }
        } else if (bearing) {
            h.setBlock(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING,
                vertical ? Direction.UP : Direction.EAST));
        } else h.setBlock(pos, AllBlocks.ROPE_PULLEY.getDefaultState());
        for (int i = 0; i < c.blocks; i++) {
            BlockPos block;
            if (bearing) block = (vertical ? pos.above() : pos.east()).south(c.shape.equals("balanced") ? i - 2 : i);
            else {
                var anchor = gantry ? pos.east(2) : pos.below();
                block = switch (c.shape) {
                    case "column" -> anchor.below(i);
                    case "cube" -> anchor.offset(i % 4, -(i / 16), (i / 4) % 4);
                    default -> anchor.offset(i % 4, 0, i / 4);
                };
            }
            h.setBlock(block, Blocks.SLIME_BLOCK);
        }
        if (c.flywheel) {
            Direction.Axis axis = ((IRotate) h.getBlockState(pos).getBlock()).getRotationAxis(h.getBlockState(pos));
            var wheel = gantry ? pos.above(5) : pos.relative(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE));
            h.setBlock(wheel, AllBlocks.FLYWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, axis));
        }
        Probe[] probe = {null};
        h.onEachTick(() -> { if (probe[0] != null) probe[0].tick(); });
        h.runAfterDelay(5, () -> {
            KineticBlockEntity be = h.getBlockEntity(pos);
            NetworkTopology.mark(be); NetworkTopology.flush();
            AbstractContraptionEntity root;
            Supplier<KineticBlockEntity> owner;
            if (bearing) {
                var b = (MechanicalBearingBlockEntity) be; b.assemble(); root = b.getMovedContraption(); owner = () -> b;
            } else if (gantry) {
                ElasticKinetics.state(be).physicalSpeed = 16;
                h.setBlock(pos.east(), AllBlocks.GANTRY_CARRIAGE.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
                GantryCarriageBlockEntity carriage = h.getBlockEntity(pos.east()); carriage.queueAssembly(); carriage.tick();
                var entity = java.util.stream.StreamSupport.stream(h.getLevel().getAllEntities().spliterator(), false)
                    .filter(e -> e instanceof GantryContraptionEntity).map(e -> (GantryContraptionEntity) e)
                    .filter(e -> e.getAnchorVec().distanceTo(Vec3.atLowerCornerOf(h.absolutePos(pos.east()))) < 1).findFirst().orElseThrow();
                entity.tick(); ContraptionPhysics.bindGantry(entity, Double.NaN); root = entity;
                owner = () -> ContraptionPhysics.gantryShaft(entity);
            } else {
                var a = (LinearActuatorBlockEntity) be; ElasticKinetics.state(be).physicalSpeed = 16;
                try { ((LinearActuatorAccessor) a).differentialcreate$assemble(); }
                catch (Exception failure) { throw new AssertionError("pulley assembly", failure); }
                root = a.movedContraption; owner = () -> a;
            }
            h.assertTrue(root != null && !root.isRemoved(), "measurement structure assembled");
            NetworkTopology.mark(be); NetworkTopology.flush();
            for (var member : be.getOrCreateNetwork().members.keySet()) {
                ElasticKinetics.state(member).physicalSpeed = 0;
                var feed = ActorFeed.of(member); feed.speed = feed.angle = feed.twist = 0;
            }
            ContraptionMotion.forget(root);
            probe[0] = new Probe(h, c, root, owner);
        });
    }
    private static final class Probe {
        final GameTestHelper h; final Case spec; final AbstractContraptionEntity root;
        final Supplier<KineticBlockEntity> owner; final ContraptionGeometry.Shape shape;
        final List<String> rows = new ArrayList<>(); final long start;
        final double initialY; double maximumDrop, maximumOutput; boolean finished;
        Probe(GameTestHelper h, Case spec, AbstractContraptionEntity root, Supplier<KineticBlockEntity> owner) {
            this.h = h; this.spec = spec; this.root = root; this.owner = owner;
            shape = ContraptionPhysics.shape(root.getContraption()); start = h.getLevel().getGameTime(); initialY = centre().y;
            rows.add("tick,time_s,centre_x_m,centre_y_m,centre_z_m,drop_m,shaft_rpm,output_rpm,output_inertia,input_network_inertia,blocks,area_y_m2,root_removed");
            sample(0);
        }
        Vec3 centre() { return root.toGlobalVector(new Vec3(.5 + shape.centre(0), .5 + shape.centre(1), .5 + shape.centre(2)), 1); }
        void sample(int tick) {
            var be = owner.get(); var p = centre();
            double shaft = be == null ? 0 : NetworkDynamics.preciseSpeed(be);
            boolean separate = be != null && ActorFeed.of(be).active;
            double output = be == null ? 0 : separate ? ActorFeed.of(be).speed : shaft;
            double inertia = be == null ? 0 : separate ? ActorFeed.of(be).inertia : ContraptionPhysics.inertia(be);
            double input = be == null ? 0 : be.getOrCreateNetwork().members.keySet().stream().mapToDouble(InertiaRegistry::getInertia).sum();
            rows.add(String.format(Locale.ROOT, "%d,%.8f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%d,%.8f,%b",
                tick, tick / 20.0, p.x, p.y, p.z, initialY - p.y, shaft, output, inertia, input, shape.blocks(), shape.translationArea()[1], root.isRemoved()));
            h.assertTrue(Double.isFinite(p.y + shaft + output), "finite measured state");
            maximumDrop = Math.max(maximumDrop, initialY - p.y);
            maximumOutput = Math.max(maximumOutput, Math.abs(output));
        }
        void tick() {
            if (finished) return;
            int tick = (int) (h.getLevel().getGameTime() - start);
            if (tick <= 0) return;
            sample(tick);
            if (tick < TICKS) return;
            finished = true;
            try {
                Path dir = Path.of(System.getProperty("differentialcreate.gravity.report", "gravity-measurement"));
                Files.createDirectories(dir); Files.write(dir.resolve(spec.name + ".csv"), rows);
            } catch (java.io.IOException failure) { throw new IllegalStateException("save gravity measurements", failure); }
            boolean control = spec.kind.endsWith("horizontal") || spec.kind.endsWith("vertical") || spec.shape.equals("balanced");
            if (control) h.assertTrue(maximumDrop < 1e-7 && maximumOutput < 1e-7, "zero projected gravity stays at rest");
            else h.assertTrue(maximumDrop > .001, "gravity moves the actual entity down");
            h.assertFalse(root.isRemoved(), "measurement kept the same assembled entity");
            System.out.println("Gravity measurement " + spec.name + ": " + rows.size() + " rows, " + shape.blocks() + " blocks");
            root.discard(); h.succeed();
        }
    }
}

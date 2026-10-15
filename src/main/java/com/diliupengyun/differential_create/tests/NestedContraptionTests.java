package com.diliupengyun.differential_create.tests;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.*;
import com.diliupengyun.differential_create.mixin.LinearActuatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

final class NestedContraptionTests {
	private NestedContraptionTests() {}
	private static final BlockPos POS = new BlockPos(8, 10, 8);
	private static void near(GameTestHelper h, double expected, double actual, double tolerance, String label) {
		h.assertTrue(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + ": expected " + expected + ", got " + actual);
	}
	private static void speed(KineticBlockEntity be, double rpm) {
		var dynamics = (INetworkDynamics) be.getOrCreateNetwork();
		dynamics.differentialcreate$setSpeed(rpm / dynamics.differentialcreate$getRatios().get(be));
	}
	private static MechanicalBearingBlockEntity assemble(GameTestHelper h, Direction facing, int radius, BlockState tool, int extension) {
		Direction radial = facing.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
		var anchor = POS.relative(facing);
		for (int i = 0; i < radius; i++) h.setBlock(anchor.relative(radial, i), Blocks.SLIME_BLOCK);
		var mount = anchor.relative(radial, radius);
		h.setBlock(mount, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, facing));
		h.setBlock(mount.relative(facing), Blocks.SLIME_BLOCK);
		for (int i = 1; i < extension; i++) h.setBlock(mount.relative(facing).relative(radial, i), Blocks.SLIME_BLOCK);
		h.setBlock(mount.relative(facing).relative(radial, extension), tool);
		MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble();
		h.assertTrue(be.isRunning(), "native bearing assembly succeeds: " + facing + ", radius=" + radius);
		NetworkTopology.mark(be); NetworkTopology.flush(); ContraptionPhysics.checkAttachments(be); ElasticKinetics.refresh(be.getOrCreateNetwork());
		return be;
	}
	private static ContraptionPhysics.Drive child(GameTestHelper h, KineticBlockEntity be) {
		var children = ContraptionPhysics.drives(be).stream().filter(ContraptionPhysics.Drive::stabilized).toList();
		h.assertTrue(children.size() == 1, "Create creates a separate stabilized entity: count=" + children.size());
		return children.getFirst();
	}
	private static double base(MechanicalBearingBlockEntity be) {
		return .01 + ContraptionPhysics.drives(be).stream().filter(d -> !d.stabilized()).mapToDouble(ContraptionPhysics.Drive::inertia).sum();
	}
	static void geometry(GameTestHelper h) {
		h.runAfterDelay(5, () -> {
			for (var facing : Direction.values()) for (int radius : new int[]{0, 2, 4}) {
				var be = assemble(h, facing, radius, Blocks.OAK_PLANKS.defaultBlockState(), 2);
				var drive = child(h, be); var root = be.getMovedContraption();
				h.assertTrue(drive.entity().getVehicle() == null, "mass is installed before the native first-tick mounting");
				h.assertTrue(root.getContraption().getBlocks().size() == radius + 1 && drive.entity().getContraption().getBlocks().size() == 3,
					"child payload is not duplicated in the parent block map");
				double expectedBase = .01;
				for (int r = 0; r <= radius; r++) expectedBase += .0001 + .0006 * r * r;
				near(h, expectedBase + 3 * .0006 * radius * radius, InertiaRegistry.getInertia(be), 1e-6, "orbital mass uses the mount radius for " + facing);
				near(h, 3 * .0006 * radius * radius, drive.inertia(), 1e-12, "stabilized body has no intrinsic spin inertia");
				near(h, 3.0 * radius * radius * radius / 409600, drive.drag(), 1e-12, "broadside child air drag at assembly angle");
				root.setAngle(90);
				near(h, 1.0 * radius * radius * radius / 409600, drive.drag(), 1e-10, "child keeps its orientation while orbital velocity turns");
				near(h, expectedBase + 3 * .0006 * radius * radius, InertiaRegistry.getInertia(be), 1e-6, "orbital mass is independent of wheel angle");
				root.setAngle(0);
				var payload = drive.entity().getContraption().getBlocks().entrySet().stream().filter(e -> e.getValue().state().is(Blocks.OAK_PLANKS)).findFirst().orElseThrow();
				drive.entity().setBlock(payload.getKey(), new StructureBlockInfo(payload.getKey(), AllBlocks.FLYWHEEL.getDefaultState(), null));
				near(h, expectedBase + 3 * .0006 * radius * radius, InertiaRegistry.getInertia(be), 1e-6, "child machine type does not add rotor inertia");
				drive.entity().setBlock(payload.getKey(), new StructureBlockInfo(payload.getKey(), Blocks.AIR.defaultBlockState(), null));
				near(h, expectedBase + 2 * .0006 * radius * radius, InertiaRegistry.getInertia(be), 1e-6, "changed child geometry invalidates parent mass");
				speed(be, 7);
				be.disassemble();
				h.assertTrue(drive.entity().isRemoved(), "immediate native disassembly also restores an unmounted child");
				near(h, .01, InertiaRegistry.getInertia(be), 1e-8, "disassembly removes parent and child mass");
				near(h, 0, ContraptionPhysics.airDrag(be), 0, "disassembly releases child air load");
				// Clear only the blocks restored by this fixture before the next orientation.
				for (var entity : List.of(root, drive.entity())) for (var entry : entity.getContraption().getBlocks().values())
					h.getLevel().setBlockAndUpdate(BlockPos.containing(entity.toGlobalVector(Vec3.atCenterOf(entry.pos()), 1)), Blocks.AIR.defaultBlockState());
				h.setBlock(POS, Blocks.AIR);
			}
			h.succeed();
		});
	}
	static void reload(GameTestHelper h) {
		h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
		h.setBlock(POS.above(), Blocks.SLIME_BLOCK);
		for (var radial : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH}) {
			h.setBlock(POS.above().relative(radial), Blocks.SLIME_BLOCK);
			h.setBlock(POS.above().relative(radial, 2), AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
			h.setBlock(POS.above(2).relative(radial, 2), Blocks.OAK_PLANKS);
		}
		h.runAfterDelay(5, () -> {
			MechanicalBearingBlockEntity original = h.getBlockEntity(POS); original.assemble(); NetworkTopology.flush();
			var root = original.getMovedContraption(); root.tick();
			var children = ContraptionPhysics.drives(original).stream().filter(ContraptionPhysics.Drive::stabilized).toList();
			h.assertTrue(children.size() == 3 && root.getPassengers().size() == 3, "native structure mounts three independent children");
			double expected = base(original) + 3 * .0006 * 4;
			speed(original, 7);
			double drag = ContraptionPhysics.airDrag(original);
			var blockTag = original.saveWithFullMetadata(h.getLevel().registryAccess());
			h.assertTrue(blockTag.getInt("DCStructureCount") == 4, "save counts all child entities");
			var rootTag = root.saveWithoutId(new CompoundTag()); rootTag.putString("id", EntityType.getKey(root.getType()).toString()); rootTag.remove("Passengers");
			var childTags = children.stream().map(d -> {
				var tag = d.entity().saveWithoutId(new CompoundTag()); tag.putString("id", EntityType.getKey(d.entity().getType()).toString()); return tag;
			}).toList();
			for (var drive : children) drive.entity().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
			root.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
			blockTag = original.saveWithFullMetadata(h.getLevel().registryAccess());
			near(h, expected, blockTag.getFloat("DCStructureInertia"), 1e-6, "controller saving after all entities unload preserves the complete mass");
			near(h, drag, blockTag.getDouble("DCStructureDrag"), 1e-12, "controller saving after child unload preserves the complete air load");
			h.assertTrue(blockTag.getInt("DCStructureCount") == 4, "unload order preserves the child count");
			var restored = (MechanicalBearingBlockEntity) BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), blockTag, h.getLevel().registryAccess());
			h.getLevel().setBlockEntity(restored); NetworkTopology.mark(restored); NetworkTopology.flush(); ElasticKinetics.refresh(restored.getOrCreateNetwork());
			near(h, expected, InertiaRegistry.getInertia(restored), 1e-6, "controller alone preserves all saved moving mass");
			near(h, drag, ContraptionPhysics.airDrag(restored), 1e-12, "controller alone preserves all saved air load");
			var loadedRoot = (ControlledContraptionEntity) EntityType.loadEntityRecursive(rootTag, h.getLevel(), e -> e);
			h.getLevel().addFreshEntity(loadedRoot); restored.attach(loadedRoot);
			for (int loaded = 0; loaded <= 3; loaded++) {
				ContraptionPhysics.checkAttachments(restored); ElasticKinetics.refresh(restored.getOrCreateNetwork());
				near(h, expected, InertiaRegistry.getInertia(restored), 1e-6, "partial child loading keeps saved mass: " + loaded);
				near(h, drag, ContraptionPhysics.airDrag(restored), 1e-12, "partial child loading keeps saved air load: " + loaded);
				near(h, 7, restored.getSpeed(), 1e-6, "child reload does not slow the same saved rotor twice");
				h.assertTrue(ContraptionPhysics.drives(restored).size() == 1 + loaded, "only loaded child entities are attached once");
				if (loaded < 3) {
					var child = EntityType.loadEntityRecursive(childTags.get(loaded), h.getLevel(), e -> e);
					h.getLevel().addFreshEntity(child); child.startRiding(loadedRoot);
				}
			}
			h.assertTrue(Float.isNaN(ElasticKinetics.state(restored).pendingStructureInertia), "last child releases the saved summary");
			var detached = ContraptionPhysics.drives(restored).stream().filter(ContraptionPhysics.Drive::stabilized).findFirst().orElseThrow();
			detached.entity().disassemble(); ContraptionPhysics.checkAttachments(restored);
			near(h, expected - .0006 * 4, InertiaRegistry.getInertia(restored), 1e-6, "separately dismantled child releases only its own mass");
			h.assertTrue(restored.saveWithFullMetadata(h.getLevel().registryAccess()).getInt("DCStructureCount") == 3, "dismantled child's UUID cannot become a phantom saved attachment");
			restored.disassemble(); near(h, .01, InertiaRegistry.getInertia(restored), 1e-8, "loaded tree disassembles completely");
			h.succeed();
		});
	}
	static void actors(GameTestHelper h) {
		h.runAfterDelay(5, () -> {
			for (int radius : new int[]{0, 2, 4}) {
				var be = assemble(h, Direction.EAST, radius, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
				var drive = child(h, be); var pair = drive.entity().getContraption().getActors().getFirst(); var context = pair.getRight();
				var behaviour = MovementBehaviour.REGISTRY.get(context.state);
				var target = h.absolutePos(new BlockPos(20, 10, 20)); context.position = Vec3.atCenterOf(target); context.relativeMotion = context.motion = new Vec3(1, 0, 0);
				h.assertTrue(ContraptionActorWork.supported(context), "child's actors are owned by the main shaft before mounting");
				near(h, radius / ContraptionGeometry.LINEAR_RADIUS, drive.workRatio(context), 1e-12, "tool's internal offset does not increase its stable orbital speed");
				for (double[] schedule : new double[][]{{16}, {512}, {0, 32, -64, 128}}) {
					h.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState()); behaviour.visitNewPosition(context, target);
					drive.entity().getContraption().stalled = true; be.getMovedContraption().getContraption().stalled = true;
					var work = ContraptionActorWork.of(context);
					var collectedWork = work;
					h.assertTrue(MachineWork.collect(List.of(be.getOrCreateNetwork())).stream().filter(b -> b.work() == collectedWork).count() == 1, "child work is collected exactly once");
					for (int repeat = 0; repeat < 10; repeat++) { speed(be, 0); behaviour.tick(context); MachiningClock.advance(h.getLevel()); }
					h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "child drill cannot use wall ticks to work at zero shaft speed");
					if (radius == 0) {
						for (int repeat = 0; repeat < 10; repeat++) { speed(be, 512); behaviour.tick(context); MachiningClock.advance(h.getLevel()); }
						h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "coaxial stabilized drill cannot work when only the parent rotates");
						near(h, 0, MachineActivity.impact(be), 0, "stationary child cannot consume imaginary tool torque");
						behaviour.cancelStall(context); continue;
					}
					double spent = 0; int step = 0;
					while (Double.isFinite(work.differentialcreate$remainingTravel()) && step < 2000) {
						speed(be, schedule[step % schedule.length]); double before = ElasticKinetics.kineticEnergy(be.getOrCreateNetwork());
						MachiningClock.advance(h.getLevel()); spent += before - ElasticKinetics.kineticEnergy(be.getOrCreateNetwork());
						if (step++ == 3 && Double.isFinite(work.differentialcreate$remainingTravel())) {
							double remaining = work.differentialcreate$remainingTravel();
							context = MovementContext.readNBT(h.getLevel(), pair.getLeft(), context.writeToNBT(new CompoundTag()), drive.entity().getContraption()); pair.setRight(context); work = ContraptionActorWork.of(context);
							near(h, remaining, work.differentialcreate$remainingTravel(), 1e-9, "child actor reload retains fractional work and shaft ownership");
						}
					}
					h.assertTrue(step < 2000 && h.getLevel().getBlockState(target).isAir(), "child drill completes through the production physical scheduler");
					near(h, 5, spent, 1e-7, "child work energy is independent of RPM, direction and orbit radius");
				}
				// Put the entire tree at its assembly angle for native teardown.
				be.getMovedContraption().setAngle(0); be.disassemble();
				for (var p : new BlockPos[]{POS, POS.east(), POS.east().above(radius), POS.east(2).above(radius), POS.east(2).above(radius + 1), POS.east(2).above(radius + 2), POS.east(2).above(radius + 3)}) h.setBlock(p, Blocks.AIR);
				for (int r = 0; r < radius; r++) h.setBlock(POS.east().above(r), Blocks.AIR);
			}
			h.succeed();
		});
	}
	static void translation(GameTestHelper h) {
		h.runAfterDelay(5, () -> {
			var config = AllConfigs.server().kinetics.stabiliseStableContraptions; boolean previous = config.get();
			try {
				for (boolean separated : new boolean[]{false, true}) {
					config.set(separated);
					h.setBlock(POS, AllBlocks.ROPE_PULLEY.getDefaultState()); h.setBlock(POS.below(), Blocks.SLIME_BLOCK);
					h.setBlock(POS.below().east(), AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
					h.setBlock(POS.below().east(2), Blocks.SLIME_BLOCK); h.setBlock(POS.below().east(3), Blocks.SLIME_BLOCK);
					h.setBlock(POS.below().east(4), Blocks.OAK_PLANKS);
					LinearActuatorBlockEntity be = h.getBlockEntity(POS); NetworkTopology.mark(be); NetworkTopology.flush(); be.setSpeed(16); speed(be, 16);
					try { ((LinearActuatorAccessor) be).differentialcreate$assemble(); } catch (Exception e) { throw new AssertionError(e); }
					h.assertTrue(be.running, "native pulley assembles with stabilized separation=" + separated);
					ContraptionPhysics.checkAttachments(be); NetworkTopology.flush(); speed(be, 16);
					near(h, .01 + 5 * .0006 * Math.pow(ContraptionGeometry.LINEAR_RADIUS, 2), InertiaRegistry.getInertia(be), 1e-7, "translation includes every cube regardless of separation");
					near(h, 5.0 / 409600 * Math.pow(ContraptionGeometry.LINEAR_RADIUS, 3), ContraptionPhysics.airDrag(be), 1e-12, "moving parent and child share their actual translational air resistance");
					if (separated) {
						var drive = child(h, be); near(h, 1, drive.workRatio(null), 0, "translating child uses the native linear drive ratio");
						near(h, 3 * .0006 * Math.pow(ContraptionGeometry.LINEAR_RADIUS, 2), drive.inertia(), 1e-12, "child's radius does not affect linear mass");
					}
					be.disassemble(); near(h, .01, InertiaRegistry.getInertia(be), 1e-8, "pulley teardown removes the whole tree");
					for (int x = 0; x <= 4; x++) h.setBlock(POS.below().east(x), Blocks.AIR);
					h.setBlock(POS, Blocks.AIR);
				}
			} finally { config.set(previous); }
			h.succeed();
		});
	}
}

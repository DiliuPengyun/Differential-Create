package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.INetworkDynamics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Physics regression suite for the continuous kinetic model (DESIGN.md
 * §2/4/5/6). Sources are real water wheels (k = 4 su/RPM², ω0 = 16 RPM, stall
 * torque 64 su/RPM, J = 0.15 su/RPM³ with Create's default stress config);
 * loads are crushing wheels (impact 8, J = 0.01 each).
 * <p>
 * All scenarios share one structure: the GameTest runner places the
 * structures of one batch directly adjacent, so separate test instances
 * would merge into one network. Assertions are gated on the physical state
 * (settle conditions), not fixed tick times, so they survive inertia
 * retuning.
 * <p>
 * The wheels' flow score is pinned: the lazy flow detection would otherwise
 * clear it before placed water spreads (killing the network mid-test), and
 * flowing water inside the structure makes the GameTest map display churn
 * thousands of log lines per tick. The physics under test do not depend on
 * the flow value's origin.
 */
@EventBusSubscriber(modid = DifferentialCreate.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class KineticPhysicsTests {

	private static final String TEMPLATE = "physics_test";
	private static final int TOTAL_CHECKS = 14;

	// Scenario anchors
	private static final BlockPos NOLOAD_WHEEL = new BlockPos(2, 2, 2);
	private static final BlockPos LOADED_WHEEL = new BlockPos(2, 2, 8);
	private static final BlockPos STALLED_WHEEL = new BlockPos(2, 2, 14);
	private static final BlockPos MERGE_WHEEL_A = new BlockPos(14, 2, 2);
	private static final BlockPos MERGE_WHEEL_B = new BlockPos(18, 2, 2);
	private static final BlockPos MERGE_CONNECTOR = new BlockPos(17, 2, 2);
	private static final BlockPos COAST_WHEEL = new BlockPos(2, 2, 18);
	private static final BlockPos COAST_SHAFT = new BlockPos(3, 2, 18);
	private static final BlockPos COAST_LOADED_WHEEL = new BlockPos(2, 2, 24);
	private static final BlockPos COAST_LOADED_SHAFT = new BlockPos(3, 2, 24);
	private static final BlockPos REVERSE_WHEEL_A = new BlockPos(14, 2, 18);
	private static final BlockPos REVERSE_WHEEL_B = new BlockPos(18, 2, 18);
	private static final BlockPos REVERSE_CONNECTOR = new BlockPos(17, 2, 18);
	private static final BlockPos WATER_WHEEL = new BlockPos(8, 2, 24);
	private static final BlockPos WATER_SHAFT = new BlockPos(9, 2, 24);
	private static final BlockPos PRESS_WHEEL = new BlockPos(2, 2, 20);
	private static final BlockPos PRESS = new BlockPos(4, 2, 20);
	private static final BlockPos UNIFORM_WHEEL = new BlockPos(8, 2, 20);

	@SubscribeEvent
	public static void register(RegisterGameTestsEvent event) {
		event.register(KineticPhysicsTests.class);
	}

	@GameTest(template = TEMPLATE, templateNamespace = "differentialcreate", timeoutTicks = 500)
	public static void physicsRegression(GameTestHelper helper) {
		// The GameTest server drops structures at random far coordinates where
		// no player ticket keeps chunks entity-ticking: without forcing them,
		// block entities tick late or never, networks form partially and the
		// physics become non-deterministic (flaky scenes 8/9/10). Pin every
		// chunk the structure touches (plus a one-chunk margin) for the run.
		var bounds = helper.getBounds();
		for (int cx = (((int) Math.floor(bounds.minX)) >> 4) - 1; cx <= (((int) Math.floor(bounds.maxX)) >> 4) + 1; cx++)
			for (int cz = (((int) Math.floor(bounds.minZ)) >> 4) - 1; cz <= (((int) Math.floor(bounds.maxZ)) >> 4) + 1; cz++)
				helper.getLevel().setChunkForced(cx, cz, true);

		// Scene 1: no-load water wheel -> converges to ω0 = 16 RPM
		WaterWheelBlockEntity noLoad = placeWheel(helper, NOLOAD_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 2), shaftX());
		helper.setBlock(new BlockPos(4, 2, 2), shaftX());

		// Scene 2: water wheel with τ = 24 load (3 crushing wheels) -> 16 − 24/4 = 10 RPM
		WaterWheelBlockEntity loaded = placeWheel(helper, LOADED_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 8), shaftX());
		for (int i = 0; i < 3; i++)
			helper.setBlock(new BlockPos(4 + i, 2, 8), crusherX());

		// Scene 3: water wheel with τ = 80 load (10 crushing wheels) > stall
		// torque 64 -> sticks at 0
		WaterWheelBlockEntity stalled = placeWheel(helper, STALLED_WHEEL, 1, null);
		helper.setBlock(new BlockPos(3, 2, 14), shaftX());
		for (int i = 0; i < 10; i++)
			helper.setBlock(new BlockPos(4 + i, 2, 14), crusherX());

		// Scene 4: gentle same-direction merge passes the energy check.
		// A: lone wheel (16 RPM, J = 0.15). B: wheel with τ = 8 (equilibrium
		// 14 RPM, J = 0.16). Δ = 2 → E ≈ 0.16 < E_BREAK, momentum merge:
		// (0.15×16 + 0.16×14)/0.31 ≈ 14.97 RPM — and no block is destroyed.
		WaterWheelBlockEntity wheelA = placeWheel(helper, MERGE_WHEEL_A, 1, null);
		helper.setBlock(new BlockPos(15, 2, 2), shaftX());
		helper.setBlock(new BlockPos(16, 2, 2), shaftX());
		WaterWheelBlockEntity wheelB = placeWheel(helper, MERGE_WHEEL_B, 1, null);
		helper.setBlock(new BlockPos(19, 2, 2), shaftX());
		helper.setBlock(new BlockPos(20, 2, 2), crusherX());

		// Scene 5+6: source removal — coasting on inertia, empty and loaded
		WaterWheelBlockEntity coast = placeWheel(helper, COAST_WHEEL, 1, null);
		helper.setBlock(COAST_SHAFT, shaftX());
		helper.setBlock(new BlockPos(4, 2, 18), shaftX());
		WaterWheelBlockEntity coastLoaded = placeWheel(helper, COAST_LOADED_WHEEL, 1, null);
		helper.setBlock(COAST_LOADED_SHAFT, shaftX());
		for (int i = 0; i < 3; i++)
			helper.setBlock(new BlockPos(4 + i, 2, 24), crusherX());

		// Scene 7: reverse connection — counter-rotating networks must destroy
		// the bridging block (E ≫ E_BREAK)
		WaterWheelBlockEntity reverseA = placeWheel(helper, REVERSE_WHEEL_A, 1, null);
		helper.setBlock(new BlockPos(15, 2, 18), shaftX());
		helper.setBlock(new BlockPos(16, 2, 18), shaftX());
		WaterWheelBlockEntity reverseB = placeWheel(helper, REVERSE_WHEEL_B, -1, null);
		helper.setBlock(new BlockPos(19, 2, 18), shaftX());

		// Scene 8: water removal — the deactivated wheel stays as an inert
		// member and the network coasts down with its inertia included
		boolean[] waterPinned = { true };
		WaterWheelBlockEntity water = placeWheel(helper, WATER_WHEEL, 1, waterPinned);
		helper.setBlock(WATER_SHAFT, shaftX());
		for (int i = 0; i < 3; i++)
			helper.setBlock(new BlockPos(10 + i, 2, 24), crusherX());

		// Scene 9: a mechanical press keeps working off the network's inertia
		// after the source is removed (consumes stored kinetic energy)
		boolean[] pressPinned = { true };
		WaterWheelBlockEntity pressWheel = placeWheel(helper, PRESS_WHEEL, 1, pressPinned);
		helper.setBlock(new BlockPos(3, 2, 20), shaftX());
		helper.setBlock(PRESS, AllBlocks.MECHANICAL_PRESS.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		// The template's floor slab does not reliably survive structure
		// placement at the GameTest server's far coordinates (terrain replaces
		// it): place the block the pressed item rests on explicitly, or the
		// item lands one block below the press's scan box.
		helper.setBlock(new BlockPos(4, 0, 20), Blocks.STONE.defaultBlockState());

		// Scene 10: after deactivation, every member's speed is exactly
		// r_i × ω of the shared network state — all the way to zero
		boolean[] uniformPinned = { true };
		WaterWheelBlockEntity uniform = placeWheel(helper, UNIFORM_WHEEL, 1, uniformPinned);
		helper.setBlock(new BlockPos(9, 2, 20), shaftX());
		for (int i = 0; i < 3; i++)
			helper.setBlock(new BlockPos(10 + i, 2, 20), crusherX());

		int[] done = { 0 };
		boolean[] mergeBridged = { false };
		boolean[] coastDug = { false };
		boolean[] reverseConnected = { false };
		boolean[] waterRemoved = { false };

		helper.onEachTick(() -> {
			// Scene 1+2: settle polls
			if ((done[0] & (1 << 0)) == 0 && closeTo(speedOf(noLoad), 16, 0.5)) {
				done[0] |= 1 << 0;
			}
			if ((done[0] & (1 << 1)) == 0 && closeTo(speedOf(loaded), 10, 0.5)) {
				done[0] |= 1 << 1;
			}

			// Scene 4: bridge once both settled, then check the gentle merge
			if (!mergeBridged[0] && closeTo(speedOf(wheelA), 16, 0.5) && closeTo(speedOf(wheelB), 14, 0.5)) {
				helper.setBlock(MERGE_CONNECTOR, shaftX());
				mergeBridged[0] = true;
				return;
			}
			if (mergeBridged[0] && (done[0] & (1 << 2)) == 0) {
				KineticNetwork netA = wheelA.getOrCreateNetwork();
				KineticNetwork netB = wheelB.getOrCreateNetwork();
				if (netA == null || netB == null || netA != netB)
					return;
				assertSpeed(wheelA, 14.97, 0.75, "momentum-averaged merge");
				if (!helper.getBlockState(MERGE_CONNECTOR)
					.isAir())
					done[0] |= 1 << 2;
				else
					helper.fail("gentle merge destroyed the bridge block");
			}

			// Scene 5+6: dig the sources once both coast networks settled
			// (no-load settles at 16, loaded at 10)
			if (!coastDug[0] && closeTo(speedOf(coast), 16, 0.2) && closeTo(speedOf(coastLoaded), 10, 0.2)) {
				coastDug[0] = true;
				helper.setBlock(COAST_WHEEL, Blocks.AIR.defaultBlockState());
				helper.setBlock(COAST_LOADED_WHEEL, Blocks.AIR.defaultBlockState());
				// No-load coast (L = 0): still 16 after 20 ticks
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_SHAFT), 16, 0.8,
						"coasting no-load network");
					done[0] |= 1 << 3;
				});
				// Loaded coast from 10 RPM: J = 3×0.01, decay L/J = 24/0.03
				// ≈ 0.667 RPM/tick -> 10 − 6.67 ≈ 3.33 after 10 ticks, 0 after 20
				helper.runAfterDelay(10, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_LOADED_SHAFT), 3.33, 0.75,
						"coasting loaded network (10 ticks)");
					done[0] |= 1 << 4;
				});
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(COAST_LOADED_SHAFT), 0, 0.01,
						"coasting loaded network stopped");
					done[0] |= 1 << 5;
				});
			}

			// Scene 7: connect the counter-rotating networks once settled
			if (!reverseConnected[0] && closeTo(speedOf(reverseA), 16, 0.5) && closeTo(speedOf(reverseB), 16, 0.5)) {
				reverseConnected[0] = true;
				helper.setBlock(REVERSE_CONNECTOR, shaftX());
				helper.runAfterDelay(15, () -> {
					if (!helper.getBlockState(REVERSE_CONNECTOR)
						.isAir())
						helper.fail("reverse connection should have destroyed the bridge block");
					done[0] |= 1 << 6;
				});
			}

			// Scene 8: deactivate the wheel once settled at 10 RPM — the network
			// coasts with the inert wheel included (J = 0.18, decay ≈ 0.111
			// RPM/tick: 10 − 2.22 ≈ 7.78 at +20, 10 − 4.44 ≈ 5.56 at +40)
			if (!waterRemoved[0] && closeTo(speedOf(water), 10, 0.2)) {
				waterRemoved[0] = true;
				waterPinned[0] = false;
				water.setFlowScoreAndUpdate(0);
				helper.runAfterDelay(20, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(WATER_SHAFT), 7.78, 0.5,
						"water removed coasting (20 ticks)");
					done[0] |= 1 << 7;
				});
				helper.runAfterDelay(40, () -> {
					assertSpeed((KineticBlockEntity) helper.getBlockEntity(WATER_SHAFT), 5.56, 0.5,
						"water removed coasting (40 ticks)");
					done[0] |= 1 << 8;
				});
			}

			// Scene 9: once settled at 14 RPM (16 − 8/4), deactivate the source
			// (water removed, keep-inert so the wheel's J stays) and drop an
			// iron ingot under the press — it must start and finish one
			// pressing cycle off the network's stored energy
			boolean[] pressFed = { false };
			// The item must be spawned into a chunk that is already
			// entity-ticking: the structure sits at random far coordinates
			// where the forced-chunk promotion can lag behind the settle gate,
			// and an item spawned into a non-ticking chunk freezes mid-air
			// (the press's scan skips it unless it is onGround).
			BlockPos itemPos = helper.absolutePos(new BlockPos(4, 1, 20));
			if (!pressFed[0] && closeTo(speedOf(pressWheel), 14, 0.5)
				&& helper.getLevel().isPositionEntityTicking(itemPos)) {
				pressFed[0] = true;
				pressPinned[0] = false;
				pressWheel.setFlowScoreAndUpdate(0);
				ItemEntity dropped = new ItemEntity(helper.getLevel(),
					itemPos.getX() + 0.5, itemPos.getY() + 0.6, itemPos.getZ() + 0.5,
					new ItemStack(Items.IRON_INGOT));
				helper.getLevel().addFreshEntity(dropped);
				helper.runAfterDelay(40, () -> {
					MechanicalPressBlockEntity press = (MechanicalPressBlockEntity) helper.getBlockEntity(PRESS);
					if (!press.getPressingBehaviour().running)
						helper.fail("press did not start on a coasting network");
					done[0] |= 1 << 10;
				});
				helper.runAfterDelay(220, () -> {
					MechanicalPressBlockEntity press = (MechanicalPressBlockEntity) helper.getBlockEntity(PRESS);
					if (!press.getPressingBehaviour().finished)
						helper.fail("press did not finish a cycle on a coasting network");
					done[0] |= 1 << 11;
				});
			}

			// Scene 10: deactivate once settled at 10 RPM, then verify member
			// consistency during the coast-down and at standstill
			boolean[] uniformRemoved = { false };
			if (!uniformRemoved[0] && closeTo(speedOf(uniform), 10, 0.2)) {
				uniformRemoved[0] = true;
				uniformPinned[0] = false;
				uniform.setFlowScoreAndUpdate(0);
				helper.runAfterDelay(30, () -> {
					assertMembersConsistent(uniform, "coasting members at +30 ticks");
					done[0] |= 1 << 12;
				});
				helper.runAfterDelay(130, () -> {
					KineticNetwork network = uniform.getOrCreateNetwork();
					if (network == null) {
						// Dissolved is fine: every member must then read 0
						for (BlockPos pos : List.of(UNIFORM_WHEEL, new BlockPos(9, 2, 20), new BlockPos(10, 2, 20),
							new BlockPos(11, 2, 20), new BlockPos(12, 2, 20))) {
							float speed = ((KineticBlockEntity) helper.getBlockEntity(pos)).getSpeed();
							if (Math.abs(speed) > 0.01f)
								helper.fail("member at " + pos + " still shows " + speed + " RPM after standstill");
						}
					} else {
						float dcSpeed = ((INetworkDynamics) network).differentialcreate$getSpeed();
						if (Math.abs(dcSpeed) > 0.01f)
							helper.fail("coasting network did not stop: " + dcSpeed);
						for (KineticBlockEntity member : network.members.keySet()) {
							float speed = member.getSpeed();
							if (Math.abs(speed) > 0.01f)
								helper.fail("member " + member.getBlockPos() + " still shows " + speed
									+ " RPM at standstill");
						}
					}
					done[0] |= 1 << 13;
				});
			}

			if (helper.getTick() == 150 && (done[0] & (1 << 9)) == 0) {
				KineticNetwork network = stalled.getOrCreateNetwork();
				if (network == null || network.members.size() < 12)
					helper.fail("stalled network missing or incomplete: " + network);
				float speed = Math.abs(((INetworkDynamics) network).differentialcreate$getSpeed());
				if (speed > 0.01f)
					helper.fail("stalled water wheel network: expected 0 RPM but got " + speed);
				done[0] |= 1 << 9;
			}

			if (Integer.bitCount(done[0]) == TOTAL_CHECKS)
				helper.succeed();
		});
	}

	/**
	 * Places a water wheel and pins its flow score while {@code pinGate[0]} is
	 * true (null = pin forever). No water is placed: the score is the only
	 * input the physics need, and flowing water inside the structure makes
	 * the GameTest map display churn thousands of log lines per tick.
	 */
	private static WaterWheelBlockEntity placeWheel(GameTestHelper helper, BlockPos pos, int flowScore,
		boolean[] pinGate) {
		helper.setBlock(pos, AllBlocks.WATER_WHEEL.get()
			.defaultBlockState()
			.setValue(WaterWheelBlock.FACING, Direction.EAST));
		WaterWheelBlockEntity wheel = (WaterWheelBlockEntity) helper.getBlockEntity(pos);
		wheel.setFlowScoreAndUpdate(flowScore);
		helper.onEachTick(() -> {
			if ((pinGate == null || pinGate[0]) && !wheel.isRemoved() && wheel.flowScore != flowScore)
				wheel.setFlowScoreAndUpdate(flowScore);
		});
		return wheel;
	}

	private static BlockState shaftX() {
		return AllBlocks.SHAFT.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
	}

	private static BlockState crusherX() {
		return AllBlocks.CRUSHING_WHEEL.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
	}

	/** Physical network speed magnitude (RPM), or null while the network is absent. */
	private static Float speedOf(KineticBlockEntity kbe) {
		KineticNetwork network = kbe.getOrCreateNetwork();
		return network == null ? null : Math.abs(((INetworkDynamics) network).differentialcreate$getSpeed());
	}

	private static boolean closeTo(Float actual, double expected, double tolerance) {
		return actual != null && Math.abs(actual - expected) <= tolerance;
	}

	/** Asserts every member of the wheel's network spins at exactly r_i × ω. */
	private static void assertMembersConsistent(KineticBlockEntity anyMember, String what) {
		KineticNetwork network = anyMember.getOrCreateNetwork();
		if (network == null)
			throw new GameTestAssertException(what + ": no network");
		INetworkDynamics dynamics = (INetworkDynamics) network;
		float omega = dynamics.differentialcreate$getSpeed();
		for (KineticBlockEntity member : network.members.keySet()) {
			Float ratio = dynamics.differentialcreate$getRatios()
				.get(member);
			if (ratio == null)
				continue;
			float expected = ratio * omega;
			float actual = member.getSpeed();
			if (Math.abs(actual - expected) > 0.01f)
				throw new GameTestAssertException(
					what + ": member " + member.getBlockPos() + " at " + actual + " RPM, expected " + expected);
		}
	}

	private static void assertSpeed(KineticBlockEntity kbe, double expected, double tolerance, String what) {
		Float actual = speedOf(kbe);
		if (actual == null || Math.abs(actual - expected) > tolerance)
			throw new GameTestAssertException(
				what + ": expected " + expected + " RPM but got " + (actual == null ? "no network" : actual));
	}

}

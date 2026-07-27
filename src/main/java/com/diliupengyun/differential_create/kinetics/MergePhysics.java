package com.diliupengyun.differential_create.kinetics;

import java.util.HashMap;
import java.util.Map;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.mixin.RotationPropagatorInvoker;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Physics of two kinetic networks colliding at a connection (DESIGN.md §6).
 * <p>
 * Vanilla merges networks member-by-member through the "overpower" branches
 * of RotationPropagator: there is no atomic merge event. The mixin hooks the
 * two spots with complete bilateral information:
 * <ul>
 * <li>the reverse-connection destroy check, replaced by an energy
 * criterion;</li>
 * <li>the setSource calls that move a member into the driving network — the
 * first cross-network call of a merge settles angular momentum, later calls
 * of the same cascade (same network pair, same tick) follow silently.</li>
 * </ul>
 * All speeds/inertias of the absorbed network are referred to the survivor's
 * base shaft by the factor q = (r_driver · r_conn) / r_driven, with r_conn the
 * connection modifier from the same getConveyedSpeed the propagation uses.
 */
public final class MergePhysics {

	private MergePhysics() {}

	/** Settled (survivor, absorbed) pairs of the current tick. */
	private static final Map<PairKey, Long> settledPairs = new HashMap<>();

	private record PairKey(long survivor, long absorbed) {}

	/** Called from {@link NetworkSpeedTicker#onPreTick(ServerTickEvent.Pre)}. */
	public static void onPreTick() {
		settledPairs.clear();
	}

	/**
	 * Evaluation of a network collision: the absorbed network's inertia and
	 * speed referred to the survivor's base shaft, the inelastically merged
	 * speed, and the dissipation energy at the contact.
	 */
	public record MergeEval(double q, double jSurvivor, double jAbsorbed, double wSurvivor, double wAbsorbed,
		double mergedSpeed, double energy) {}

	/**
	 * Computes the collision between the driver's network (survivor) and the
	 * driven block's network (absorbed). Returns null when the contact ratio
	 * cannot be established (missing ratios or a non-conveying connection) —
	 * callers fall back to vanilla behaviour then.
	 */
	public static MergeEval evaluate(KineticNetwork survivor, KineticNetwork absorbed, KineticBlockEntity driver,
		KineticBlockEntity driven) {
		INetworkDynamics survivorDynamics = (INetworkDynamics) survivor;
		INetworkDynamics absorbedDynamics = (INetworkDynamics) absorbed;
		Float ratioDriver = survivorDynamics.differentialcreate$getRatios()
			.get(driver);
		Float ratioDriven = absorbedDynamics.differentialcreate$getRatios()
			.get(driven);
		float driverNominal = driver.getTheoreticalSpeed();
		if (ratioDriver == null || ratioDriven == null || ratioDriver == 0 || ratioDriven == 0
			|| driverNominal == 0)
			return null;

		// Connection modifier driver-local -> driven-local, same source the
		// propagation itself uses
		float connection = RotationPropagatorInvoker.differentialcreate$invokeGetConveyedSpeed(driver, driven)
			/ driverNominal;
		if (connection == 0)
			return null;

		double q = ratioDriver * connection / ratioDriven;
		double jSurvivor = NetworkPhysics.aggregate(survivor, survivorDynamics.differentialcreate$getRatios())
			.j();
		double jAbsorbed = NetworkPhysics.aggregate(absorbed, absorbedDynamics.differentialcreate$getRatios())
			.j();
		double wSurvivor = survivorDynamics.differentialcreate$getSpeed();
		double wAbsorbed = absorbedDynamics.differentialcreate$getSpeed();

		// Dissipation energy at the contact, in the driver's local frame:
		// E = ½·μ·Δ² with μ the reduced inertia referred to the contact
		double delta = ratioDriver * wSurvivor - ratioDriven / connection * wAbsorbed;
		double jSurvivorContact = jSurvivor / (ratioDriver * ratioDriver);
		double jAbsorbedContact = jAbsorbed / (ratioDriven * ratioDriven * connection * connection);
		double jSum = jSurvivorContact + jAbsorbedContact;
		double reduced = jSum == 0 ? 0 : jSurvivorContact * jAbsorbedContact / jSum;
		double energy = 0.5 * reduced * delta * delta;

		// Inelastic merge: angular momentum conservation in the survivor's
		// base frame. A zero-inertia side degenerates to adopting the other
		// side's speed (DESIGN.md §6.1) — no special case needed.
		double jAbsorbedReferred = q * q * jAbsorbed;
		double total = jSurvivor + jAbsorbedReferred;
		double merged = total == 0 ? wSurvivor
			: (jSurvivor * wSurvivor + jAbsorbedReferred * (q * wAbsorbed)) / total;

		return new MergeEval(q, jSurvivor, jAbsorbed, wSurvivor, wAbsorbed, merged, energy);
	}

	/**
	 * Reverse-connection branch of the propagation: vanilla destroys the
	 * contact block unconditionally. Here the block only breaks when the
	 * merge dissipation exceeds the threshold; below it the networks merge
	 * inelastically (opposite speeds cancel through the signed momentum).
	 */
	public static void handleIncompatible(KineticBlockEntity currentTE, KineticBlockEntity neighbourTE,
		CallbackInfo ci) {
		Level level = currentTE.getLevel();
		if (level == null || level.isClientSide || !currentTE.hasNetwork() || !neighbourTE.hasNetwork())
			return; // cannot evaluate: keep vanilla destroy
		KineticNetwork currentNetwork = currentTE.getOrCreateNetwork();
		KineticNetwork neighbourNetwork = neighbourTE.getOrCreateNetwork();
		if (currentNetwork == null || neighbourNetwork == null || currentNetwork == neighbourNetwork)
			return;

		// Replicate vanilla's overpower choice to know which network survives
		float newSpeed = RotationPropagatorInvoker.differentialcreate$invokeGetConveyedSpeed(currentTE, neighbourTE);
		float oppositeSpeed = RotationPropagatorInvoker.differentialcreate$invokeGetConveyedSpeed(neighbourTE,
			currentTE);
		KineticBlockEntity driver, driven;
		KineticNetwork survivor, absorbed;
		if (Math.abs(oppositeSpeed) > Math.abs(currentTE.getTheoreticalSpeed())) {
			driver = neighbourTE;
			survivor = neighbourNetwork;
			driven = currentTE;
			absorbed = currentNetwork;
		} else {
			driver = currentTE;
			survivor = currentNetwork;
			driven = neighbourTE;
			absorbed = neighbourNetwork;
		}

		// A clutch contact uses its own (higher) threshold and breaks the
		// clutch itself on a violent engagement: the slip is the cushion
		boolean clutchMediated = driver instanceof ClutchBlockEntity || driven instanceof ClutchBlockEntity;
		double threshold = clutchMediated ? PhysicsTuning.CLUTCH_BREAK_ENERGY : PhysicsTuning.MERGE_BREAK_ENERGY;

		MergeEval eval = evaluate(survivor, absorbed, driver, driven);
		double energy = eval == null ? Double.MAX_VALUE : eval.energy();
		if (energy > threshold) {
			if (clutchMediated) {
				ci.cancel(); // vanilla would break the contact block
				KineticBlockEntity clutch = driver instanceof ClutchBlockEntity ? driver : driven;
				level.destroyBlock(clutch.getBlockPos(), true);
				DifferentialCreate.LOGGER.debug(
					"Clutch at {} destroyed by violent engagement of networks {} and {}: E={} > E_CLUTCH_MAX={} (destroyed=true)",
					clutch.getBlockPos().toShortString(), survivor.id, absorbed.id, fmt(energy), fmt(threshold));
				return;
			}
			DifferentialCreate.LOGGER.debug(
				"Kinetic networks {} and {} collided at {} RPM difference: E={} > E_BREAK={}, destroying contact block (destroyed=true)",
				survivor.id, absorbed.id, fmt(eval == null ? 0 : Math.abs(eval.wSurvivor() - eval.wAbsorbed())),
				fmt(energy), fmt(PhysicsTuning.MERGE_BREAK_ENERGY));
			return; // let vanilla destroy the contact block
		}

		// Skip the destroy; vanilla's overpower branches below combine the
		// networks. Momentum settles here — the setSource hook of the same
		// merge finds the pair already settled. A clutch-mediated merge needs
		// no settle: the slip already cushioned the engagement.
		ci.cancel();
		if (clutchMediated)
			settleClutchMerge(survivor, absorbed, driver, driven, eval, threshold);
		else
			settleMomentum(survivor, absorbed, driver, driven, eval);
	}

	/**
	 * Hooked into the setSource calls of the propagation's overpower branches:
	 * the moment a member is about to join the driving block's network. Every
	 * genuine two-network merge — any direction — passes the same energy
	 * criterion (DESIGN.md §6): above the break energy the contact-side
	 * member is knocked off instead of merging, below it the networks merge
	 * inelastically with momentum conserved. A clutch contact uses its own
	 * threshold and breaks the clutch itself.
	 */
	public static void handleSetSource(KineticBlockEntity driven, BlockPos sourcePos) {
		Level level = driven.getLevel();
		if (level != null && !level.isClientSide && driven.hasNetwork()) {
			BlockEntity blockEntity = level.getBlockEntity(sourcePos);
			if (blockEntity instanceof KineticBlockEntity driver && driver.hasNetwork()
				&& !driven.network.equals(driver.network)) {
				KineticNetwork survivor = driver.getOrCreateNetwork();
				KineticNetwork absorbed = driven.getOrCreateNetwork();
				if (survivor != null && absorbed != null && survivor != absorbed) {
					boolean clutchMediated = driver instanceof ClutchBlockEntity || driven instanceof ClutchBlockEntity;
					double threshold =
						clutchMediated ? PhysicsTuning.CLUTCH_BREAK_ENERGY : PhysicsTuning.MERGE_BREAK_ENERGY;
					MergeEval eval = evaluate(survivor, absorbed, driver, driven);
					double energy = eval == null ? Double.MAX_VALUE : eval.energy();
					if (energy > threshold) {
						// Violent merge: knock the contact block off instead of
						// combining. Destroying the driven (absorbed-side) block
						// also ends the propagation cascade cleanly, because the
						// recursion then runs on air.
						KineticBlockEntity broken = clutchMediated
							? (driver instanceof ClutchBlockEntity ? driver : driven)
							: driven;
						level.destroyBlock(broken.getBlockPos(), true);
						DifferentialCreate.LOGGER.debug(
							"{} at {} destroyed by violent merge of networks {} and {}: E={} > threshold={} (destroyed=true)",
							clutchMediated ? "Clutch" : "Contact block", broken.getBlockPos().toShortString(),
							survivor.id, absorbed.id, fmt(energy), fmt(threshold));
						return; // do NOT setSource: the merge is aborted
					}
					if (clutchMediated)
						settleClutchMerge(survivor, absorbed, driver, driven, eval, threshold);
					else
						settleMomentum(survivor, absorbed, driver, driven, eval);
				}
			}
		}
		driven.setSource(sourcePos);
	}

	/**
	 * Reverse activation of a source inside a running network (e.g. water
	 * added to a wheel that co-rotates the other way): vanilla destroys the
	 * source on the sign flip. Here it only breaks when the engagement
	 * energy exceeds the threshold; below it the source keeps its block and
	 * its torque characteristic fights the network's motion (a physical
	 * brake, not a glitch). Returns true when the block should be spared.
	 */
	public static boolean reverseActivationSpared(com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity source) {
		Level level = source.getLevel();
		if (level == null || level.isClientSide || !source.hasNetwork())
			return false; // cannot evaluate: keep vanilla destroy
		KineticNetwork network = source.getOrCreateNetwork();
		if (network == null)
			return false;
		INetworkDynamics dynamics = (INetworkDynamics) network;
		Float ratio = dynamics.differentialcreate$getRatios()
			.get(source);
		if (ratio == null || ratio == 0)
			return false;

		double omega0 = 2.0 * source.getGeneratedSpeed();
		double networkSpeed = dynamics.differentialcreate$getSpeed();
		// Δ in the network's base frame, per the spec: |ω_network − r·ω0_new|
		double delta = networkSpeed - ratio * omega0;
		double jNetwork = NetworkPhysics.aggregate(network, dynamics.differentialcreate$getRatios())
			.j();
		double jSourceReferred = ratio * ratio * InertiaRegistry.getInertia(source);
		double jSum = jNetwork + jSourceReferred;
		double reduced = jSum == 0 ? 0 : jNetwork * jSourceReferred / jSum;
		double energy = 0.5 * reduced * delta * delta;

		boolean spared = energy <= PhysicsTuning.MERGE_BREAK_ENERGY;
		DifferentialCreate.LOGGER.debug(
			"Reverse activation of {} at {}: network ω={}, new ω0={}, Δ={}, E={} / E_BREAK={} -> {}",
			source.getBlockState().getBlock().builtInRegistryHolder().key().location(),
			source.getBlockPos().toShortString(), fmt(networkSpeed), fmt(omega0), fmt(delta), fmt(energy),
			fmt(PhysicsTuning.MERGE_BREAK_ENERGY), spared ? "spared (torque battle)" : "destroyed");
		return spared;
	}

	private static void settleMomentum(KineticNetwork survivor, KineticNetwork absorbed, KineticBlockEntity driver,
		KineticBlockEntity driven, MergeEval eval) {
		Level level = driver.getLevel();
		long gameTime = level.getGameTime();
		PairKey key = new PairKey(survivor.id == null ? 0 : survivor.id, absorbed.id == null ? 0 : absorbed.id);
		Long last = settledPairs.get(key);
		if (last != null && last == gameTime)
			return; // this cascade already settled
		if (eval == null)
			eval = evaluate(survivor, absorbed, driver, driven);
		if (eval == null)
			return; // contact ratio unknowable: keep survivor speed, no log
		settledPairs.put(key, gameTime);

		DifferentialCreate.LOGGER.debug("  merge eval: driver={}@{}, driven={}@{}, driverNominal={}, q={}",
			driver.getBlockState().getBlock().builtInRegistryHolder().key().location(), driver.getBlockPos().toShortString(),
			driven.getBlockState().getBlock().builtInRegistryHolder().key().location(), driven.getBlockPos().toShortString(),
			driver.getTheoreticalSpeed(), fmt(eval.q()));

		double totalJ = eval.jSurvivor() + eval.q() * eval.q() * eval.jAbsorbed();
		if (totalJ > 0)
			((INetworkDynamics) survivor).differentialcreate$setSpeed((float) eval.mergedSpeed());
		// both inertialess: dcSpeed is irrelevant, the next tick snaps to equilibrium

		DifferentialCreate.LOGGER.debug(
			"Kinetic networks {} (J={}, ω={}) and {} (J={}, ω={}) merged -> ω={} RPM (E={} / E_BREAK={}, destroyed=false)",
			survivor.id, fmt(eval.jSurvivor()), fmt(eval.wSurvivor()), absorbed.id, fmt(eval.jAbsorbed()),
			fmt(eval.wAbsorbed()), totalJ > 0 ? fmt(eval.mergedSpeed()) : "equilibrium",
			fmt(eval.energy()), fmt(PhysicsTuning.MERGE_BREAK_ENERGY));
	}

	/**
	 * Clutch-mediated merge: no destroy, no instant momentum averaging — the
	 * slip phase already dissipated the difference. Marks the pair so the
	 * rest of the cascade stays silent.
	 */
	private static void settleClutchMerge(KineticNetwork survivor, KineticNetwork absorbed, KineticBlockEntity driver,
		KineticBlockEntity driven, MergeEval eval, double threshold) {
		Level level = driver.getLevel();
		PairKey key = new PairKey(survivor.id == null ? 0 : survivor.id, absorbed.id == null ? 0 : absorbed.id);
		Long last = settledPairs.get(key);
		if (last != null && last == level.getGameTime())
			return;
		if (eval == null)
			eval = evaluate(survivor, absorbed, driver, driven);
		settledPairs.put(key, level.getGameTime());
		KineticBlockEntity clutch = driver instanceof ClutchBlockEntity ? driver : driven;
		DifferentialCreate.LOGGER.debug(
			"Clutch at {} merged networks {} and {} (E={} / E_CLUTCH_MAX={}, destroyed=false)",
			clutch.getBlockPos().toShortString(), survivor.id, absorbed.id, fmt(eval == null ? 0 : eval.energy()),
			fmt(threshold));
	}

	private static String fmt(double value) {
		return String.format("%.2f", value);
	}

}

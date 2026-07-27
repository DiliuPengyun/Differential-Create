package com.diliupengyun.differential_create.kinetics;

import java.util.Map;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;

/**
 * Physics of a kinetic network as a single rotational degree of freedom
 * (DESIGN.md §4.2, §5):
 *
 * <pre>
 * J_eff · dω/dt = Σ_sources r·M(r·ω) − Σ_loads |r|·τ · sign(ω) − Σ_fans r²·c·ω·|ω|
 *             = T0 − L·sign(ω) − K·ω − C·ω·|ω|
 * </pre>
 *
 * with all torques referred to the base shaft (ratio 1). Sources follow the
 * linear characteristic M(s) = k·(ω0 − s) with ω0 = 2×generated speed and
 * k = ratedSU / generated², both derived from Create's own stress config so
 * mods and datapacks retuning capacities carry over. Loads are dry (constant)
 * friction opposing the local motion — except encased fans, whose load grows
 * with the square of the local speed (DESIGN.md §3.3) and is integrated in a
 * separate exact step.
 */
public final class NetworkPhysics {

	/**
	 * One tick in minutes. Time enters the equations in RPM⁻¹ (= minutes), see
	 * DESIGN.md §1: J/K has units of 1/RPM, so the exponent K·dt/J is
	 * dimensionless when dt is in minutes.
	 */
	public static final double TICK_MINUTES = 1.0 / 1200.0;

	/**
	 * Below this distance to the equilibrium (RPM) the speed snaps to it, so
	 * converged networks stop emitting sync packets.
	 */
	private static final float SNAP_EPSILON_RPM = 0.05f;

	private NetworkPhysics() {}

	/**
	 * Network-wide aggregates referred to the base shaft:
	 *
	 * @param t0      driving torque at standstill Σ r·k·ω0 (signed)
	 * @param k       speed coefficient of all sources Σ r²·k
	 * @param l       total dry-friction load Σ |r|·τ (always positive)
	 * @param j       effective moment of inertia Σ r²·J
	 * @param fanDrag quadratic fan drag Σ r²·c with c = impact/anchor²
	 */
	public record Balance(double t0, double k, double l, double j, double fanDrag) {}

	/**
	 * @param newSpeed    network speed after the tick
	 * @param equilibrium speed the network is heading towards (0 when stuck or
	 *                     coasting without sources)
	 */
	public record StepResult(float newSpeed, float equilibrium) {}

	/**
	 * Aggregates the network equation coefficients from the current members
	 * and sources. Called every tick: source parameters (e.g. steam engine
	 * efficiency) change over time.
	 */
	public static Balance aggregate(KineticNetwork network, Map<KineticBlockEntity, Float> ratios) {
		double t0 = 0, k = 0, l = 0, j = 0, fanDrag = 0;

		for (Map.Entry<KineticBlockEntity, Float> entry : ratios.entrySet()) {
			KineticBlockEntity member = entry.getKey();
			if (member.isRemoved())
				continue;
			double r = entry.getValue();
			j += r * r * InertiaRegistry.getInertia(member);
			double impact = member.calculateStressApplied();
			if (impact > 0 && member instanceof EncasedFanBlockEntity) {
				// Fan: τ(s) = impact·(s/anchor)², referred to the base shaft as
				// quadratic drag r²·c·ω|ω| with c = impact/anchor². Anchored so
				// the fan consumes its vanilla impact at 64 RPM.
				double anchor = PhysicsTuning.FAN_ANCHOR_RPM;
				fanDrag += r * r * impact / (anchor * anchor);
			} else {
				l += Math.abs(r) * impact;
			}
		}

		for (KineticBlockEntity source : network.sources.keySet()) {
			Float ratio = ratios.get(source);
			if (source.isRemoved() || ratio == null)
				continue;
			double generated = source.getGeneratedSpeed();
			if (generated == 0)
				continue;
			double r = ratio;
			double omega0 = 2 * generated; // peak power lands on the vanilla operating point
			double ratedSU = source.calculateAddedStressCapacity() * Math.abs(generated);
			double ki = ratedSU / (generated * generated);
			t0 += r * ki * omega0;
			k += r * r * ki;
		}

		return new Balance(t0, k, l, j, fanDrag);
	}

	/**
	 * Advances the network speed by one tick: exact step of the linear part
	 * (DESIGN.md §5.1) followed by the exact fan-drag decay step (§5.2), with
	 * dry-friction stiction.
	 */
	public static StepResult integrate(float omega, Balance balance) {
		double t0 = balance.t0(), k = balance.k(), l = balance.l(), j = balance.j();

		// Stiction: at rest, the driving torque must overcome the dry load
		// torque (fans exert no torque at standstill)
		if (omega == 0 && Math.abs(t0) <= l)
			return new StepResult(0, 0);

		double next;
		double equilibrium;
		if (k > 0) {
			// Friction opposes the current motion — or the incipient one when
			// starting from rest
			double frictionSign = omega != 0 ? Math.signum(omega) : Math.signum(t0);
			equilibrium = equilibrium(t0, k, l, balance.fanDrag(), j, frictionSign);

			if (j <= 0)
				next = equilibrium; // inertialess networks snap to equilibrium
			else
				next = equilibrium + (omega - equilibrium) * Math.exp(-k * TICK_MINUTES / j);

			// Sign change within one step: stick at 0 unless the source torque
			// can restart motion in the new direction
			if (omega != 0 && Math.signum((float) next) != Math.signum(omega) && Math.abs(t0) <= l)
				next = 0;
			if (Math.abs(next - equilibrium) < SNAP_EPSILON_RPM)
				next = equilibrium;
		} else {
			// No source: dry friction decelerates the network linearly to a stop
			equilibrium = 0;
			if (l <= 0) {
				next = omega; // no friction in the model: coasts (fan drag below still applies)
			} else if (j <= 0) {
				next = 0; // inertialess: stops instantly
			} else {
				double decel = l * TICK_MINUTES / j;
				next = Math.abs(omega) <= decel ? 0 : omega - Math.signum(omega) * decel;
			}
		}

		// Exact fan-drag step: solution of dω/dt = −(C/J)·ω·|ω| (DESIGN.md
		// §5.2). Unconditionally stable, only decays. Inertialess networks
		// ignore it: they snap to the linear equilibrium above (the fan term
		// is also excluded from that equilibrium, by the J=0 convention).
		if (balance.fanDrag() > 0 && j > 0)
			next = next / (1 + balance.fanDrag() * TICK_MINUTES * Math.abs(next) / j);

		return new StepResult((float) next, (float) equilibrium);
	}

	/**
	 * Equilibrium of T0 − L·sign(ω) − K·ω − C·ω·|ω| = 0 for the given friction
	 * direction. Without fan drag this is the linear (T0 − L·sign)/K; with
	 * fans the quadratic is solved in closed form (used for settle logging;
	 * the integration step itself does not depend on it).
	 */
	private static double equilibrium(double t0, double k, double l, double fanDrag, double j, double frictionSign) {
		double a = t0 - l * frictionSign;
		if (fanDrag <= 0 || j <= 0)
			return a / k;
		if (a == 0)
			return 0;
		if (a > 0)
			return (-k + Math.sqrt(k * k + 4 * fanDrag * a)) / (2 * fanDrag);
		return (k - Math.sqrt(k * k - 4 * fanDrag * a)) / (2 * fanDrag);
	}

}

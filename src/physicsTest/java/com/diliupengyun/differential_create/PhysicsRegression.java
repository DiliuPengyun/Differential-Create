package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.RotationMath;
import com.diliupengyun.differential_create.kinetics.ClutchControl;
import com.diliupengyun.differential_create.kinetics.ClutchCoupling;
import com.diliupengyun.differential_create.kinetics.ClutchCoupling.Shaft;
import com.diliupengyun.differential_create.kinetics.WorkAccumulator;

/** Independent analytical expectations, runnable without Minecraft or a test framework. */
public final class PhysicsRegression {
	private static int checks;
	public static void main(String[] args) {
		TorsionRegression.run();
		shaftTravel();
		near(32, ClutchControl.torqueLimit(0), 0, "unpowered clutch retains full torque capacity");
		near(0, ClutchControl.torqueLimit(15), 0, "full redstone completely releases the clutch");
		near(32.0 / 15, ClutchControl.torqueLimit(14), 1e-12, "weakest engagement still transfers limited torque");
		near(224.0 / 15, ClutchControl.torqueLimit(8), 1e-12, "intermediate redstone sets a torque ceiling");
		near(1, ClutchControl.engagement(-1), 0, "signal below range clamps to full engagement");
		near(0, ClutchControl.engagement(16), 0, "signal above range clamps to disengaged");
		clutchCoupling();
		near(0.2, step(0, 32, 0, 8, 0.1, 0), 1e-12, "external torque starts a sourceless network");
		near(-0.2, step(0, -32, 0, 8, 0.1, 0), 1e-12, "negative external torque");
		near(0, step(0, 7, 4, 8, 0.1, 1), 0, "static friction");
		double equilibrium = (-4 + Math.sqrt(16 + 4 * 0.125 * 512)) / 0.25;
		near(equilibrium, step(equilibrium, 512, 4, 0, 0.1, 0.125), 1e-12, "fan equilibrium remains fixed");
		double speed = 0;
		for (int i = 0; i < 10000; i++) speed = step(speed, 512, 4, 0, 0.1, 0.125);
		near(equilibrium, speed, 1e-10, "fan converges to the equation's equilibrium");
		near(equilibrium, step(0, 512, 4, 0, 0, 0.125), 1e-12, "zero inertia retains fan load");
		near(16 * (1 - Math.exp(-4.0 / 1200 / 0.15)), step(0, 64, 4, 0, 0.15, 0), 1e-12, "waterwheel exact response");
		speed = 10;
		for (int i = 0; i < 20; i++) speed = step(speed, 0, 0, 24, 0.18, 0);
		near(10 - 24.0 / 0.18 / 60, speed, 1e-12, "dry friction coasting");
		near(0, step(0.1, 0, 0, 24, 0.01, 0), 0, "coasting never reverses");
		near(16, step(16, 8, 0, 8, 0, 0), 0, "massless loaded shaft keeps the coupled speed when torque balances");
		WorkAccumulator work = new WorkAccumulator();
		int progress = 0;
		for (int i = 0; i < 160; i++) progress += work.advance(0.5 / 16);
		near(5, progress, 0, "low-speed work has no one-unit-per-tick floor");
		work.restore(0.75);
		near(0, work.advance(0), 0, "stalled progress freezes");
		near(0.75, work.remainder(), 0, "stalled work is retained");
		near(1, work.advance(0.25), 0, "restored fractional work completes exactly once");
		work.reset();
		work.borrowOne();
		near(0, work.advance(0.25), 0, "press contact advance is charged to later travel");
		near(-0.75, work.remainder(), 0, "press contact debt is retained");
		near(0, work.advance(0.75), 0, "repaying contact debt does not advance twice");
		near(1, work.advance(1), 0, "travel resumes after repaying contact debt");
		for (double drag : new double[]{0, 0.01, 1, 100})
			for (double damping : new double[]{0, 1, 10, 100})
				for (double torque : new double[]{-64, -3, 0, 3, 64}) {
					double once = RotationMath.advance(5, torque, damping, 4, 0.1, drag, 0.2);
					double split = 5;
					for (int i = 0; i < 200; i++) split = RotationMath.advance(split, torque, damping, 4, 0.1, drag, 0.001);
					near(once, split, 1e-8, "exact solution composes across stopping and reversal");
					near(-once, RotationMath.advance(-5, -torque, damping, 4, 0.1, drag, 0.2), 1e-10, "direction symmetry");
				}
		for (double q : new double[]{0.5, 2, -0.5, -2}) {
			near(1, RotationMath.mergedSpeed(1, 1, 1, q, q), 1e-12, "synchronous geared merge");
			near(0, RotationMath.mergeLoss(1, 1, 1, q, q), 1e-12, "synchronous merge loses no energy");
			double merged = RotationMath.mergedSpeed(0.15, 16, 0.4, -3, q);
			double loss = RotationMath.mergeLoss(0.15, 16, 0.4, -3, q);
			near(0.5 * 0.15 * 256 + 0.5 * 0.4 * 9,
				0.5 * (0.15 + 0.4 * q * q) * merged * merged + loss, 1e-10, "geared merge energy balance");
		}
		mergeConservation();
		RenderingRegression.run();
		System.out.println("Physics regression: " + checks + " checks passed");
	}

	private static void shaftTravel() {
		double tick = RotationMath.TICK_MINUTES;
		for (double fraction : new double[]{1, .5, .03, 1e-4}) {
			double dt = tick * fraction;
			Shaft a = new Shaft(64, 0, 0, 0, .1, 0, 1), b = new Shaft(16, 0, 0, 8, .01, 0, 1);
			double torque = ClutchCoupling.torque(a, b, 32, dt);
			var ma = RotationMath.motion(64, -torque, 0, 0, .1, 0, dt);
			var mb = RotationMath.motion(16, torque, 0, 8, .01, 0, dt);
			double lost = .05 * (64 - ma.speed()) * (64 + ma.speed()) + .005 * (16 - mb.speed()) * (16 + mb.speed());
			near(lost, torque * (ma.angle() - mb.angle()) + 8 * mb.travel(), 1e-10, "clutch segments account for machine work and slip dissipation together");
		}
		var stop = RotationMath.motion(.1, 0, 0, 8, .01, 0, tick);
		near(0, stop.speed(), 0, "mid-tick stop");
		near(.5 * .01 * .1 * .1, stop.travel() * 8, 1e-14, "stopping travel spends exactly the available kinetic energy");
		var reversal = RotationMath.motion(1, -12, 0, 4, .01, 0, .002);
		double stopTime = .01 / 16, backTime = .002 - stopTime;
		near(.5 * stopTime + .5 * 800 * backTime * backTime, reversal.travel(), 1e-14, "reversal counts both directions of useful travel");
		near(.5 * stopTime - .5 * 800 * backTime * backTime, reversal.angle(), 1e-14, "signed angle distinguishes a reversal");
		for (double drag : new double[]{0, .01, 1, 100})
			for (double damping : new double[]{0, 1, 10, 100})
				for (double torque : new double[]{-64, -3, 0, 3, 64}) {
					var once = RotationMath.motion(5, torque, damping, 4, .1, drag, .2);
					double speed = 5, arc = 0, angle = 0;
					for (int i = 0; i < 200; i++) {
						var part = RotationMath.motion(speed, torque, damping, 4, .1, drag, .001);
						speed = part.speed(); arc += part.travel(); angle += part.angle();
					}
					near(RotationMath.advance(5, torque, damping, 4, .1, drag, .2), once.speed(), 1e-10, "motion retains the analytical speed solution");
					near(arc, once.travel(), 1e-8, "travel composes across arbitrary phase subdivisions");
					near(angle, once.angle(), 1e-8, "angle composes across stops and reversals");
				}
		for (double inertia : new double[]{.51, 896.01})
			for (double initial : new double[]{8, 16, 32, 64, 128, 256}) {
				double speed = initial, budget = 1920, integratedWork = 0;
				for (int ticks = 0; budget > 1e-8 && ticks < 10000; ticks++) {
					double start = speed, left = budget;
					double duration = com.diliupengyun.differential_create.kinetics.ShaftClock.firstBoundary(tick,
						dt -> RotationMath.motion(start, 0, 0, 8, inertia, 0, dt).travel() / tick >= left);
					var motion = RotationMath.motion(speed, 0, 0, 8, inertia, 0, duration);
					speed = motion.speed(); budget -= motion.travel() / tick;
					integratedWork += 8 * motion.travel();
				}
				near(0, budget, 1e-8, "full press reaches its exact work boundary");
				near(12.8, integratedWork, 1e-10, "same shaft budget at every speed and inertia");
				near(12.8, .5 * inertia * (initial - speed) * (initial + speed), 2e-6, "large-inertia double state retains the actual work debit");
			}
	}

	private static void mergeConservation() {
		for (double q : new double[]{-16, -2, -1, -0.5, -0.0625, 0.0625, 0.5, 1, 2, 16})
			for (double[] inertia : new double[][]{{0, 0}, {0, 0.01}, {0.16, 0},
				{0.16, 0.01}, {0.01, 0.16}, {0.15, 0.4}})
				for (double[] speed : new double[][]{{0, 0}, {3.5, 0}, {-3.5, 0}, {16, -3}, {-1, 4}, {0, -2}}) {
					double ja = inertia[0], jb = inertia[1], wa = speed[0], wb = speed[1];
					double merged = RotationMath.mergedSpeed(ja, wa, jb, wb, q);
					double momentumBefore = ja * wa + q * jb * wb;
					double momentumAfter = ja * merged + q * jb * (q * merged);
					double energyBefore = 0.5 * ja * wa * wa + 0.5 * jb * wb * wb;
					double energyAfter = 0.5 * ja * merged * merged + 0.5 * jb * (q * merged) * (q * merged);
					double loss = RotationMath.mergeLoss(ja, wa, jb, wb, q);
					near(momentumBefore, momentumAfter, 1e-12 * (1 + Math.abs(momentumBefore)),
						"engagement conserves momentum referred to the same shaft");
					near(energyBefore, energyAfter + loss, 1e-12 * (1 + energyBefore),
						"engagement energy loss accounts for both rotors");
					near(0, Math.max(0, energyAfter - energyBefore), 1e-12 * (1 + energyBefore),
						"passive engagement cannot create kinetic energy");
					near(0, Math.min(0, loss), 0, "engagement loss is nonnegative");
					near(loss, RotationMath.mergeLoss(jb, wb, ja, wa, 1 / q), 1e-12 * (1 + energyBefore),
						"energy loss is independent of which network survives");
					if (ja + jb > 0)
						near(q * merged, RotationMath.mergedSpeed(jb, wb, ja, wa, 1 / q),
							1e-12 * (1 + Math.abs(q * merged)), "survivor choice preserves both physical speeds");
				}
		// The screenshot's 14-sail windmill (J=0.16) drives a stationary
		// J=0.01 press through -16x gearing. Use independent exact fractions.
		near(3.5 / 17, RotationMath.mergedSpeed(0.16, 3.5, 0.01, 0, -16), 1e-12,
			"windmill slows to one seventeenth on hot press placement");
		near(0.98 * 16 / 17, RotationMath.mergeLoss(0.16, 3.5, 0.01, 0, -16), 1e-12,
			"windmill engagement dissipates sixteen seventeenths of its energy");
	}

	private static void clutchCoupling() {
		for (double sign : new double[]{-1, 1}) {
			// Wheel torque 64 - 4*12 = 16, reflected through 2x gearing to
			// balance an 8-torque machine. Both shafts are already at 24 RPM.
			Shaft wheel = new Shaft(12 * sign, 64 * sign, 4, 0, 0.15, 0, -2);
			Shaft machine = new Shaft(-24 * sign, 0, 0, 8, 0.01, 0, 1);
			near(-8 * sign, ClutchCoupling.torque(wheel, machine, 32), 1e-7,
				"synchronous loaded shafts still transmit holding torque");
			near(8 * sign, ClutchCoupling.torque(machine, wheel, 32), 1e-7,
				"swapping the clutch reference side reverses reaction torque");
			near(-8 * sign, ClutchCoupling.torque(wheel, machine, ClutchControl.torqueLimit(8)), 1e-7,
				"partial pressure can hold a synchronous load");
			near(0, ClutchCoupling.torque(wheel, machine, 0), 0, "released clutch has no holding torque");
		}
		Shaft rotor = new Shaft(1, 0, 0, 0, 0.15, 0, 1);
		Shaft stopped = new Shaft(0, 0, 0, 0, 0.01, 0, 1);
		near(11.25, ClutchCoupling.torque(rotor, stopped, 32), 1e-7,
			"unloaded synchronization agrees with conservation of angular momentum");
		Shaft motor = new Shaft(0, 32, 1, 0, 0.0375, 0, 1);
		Shaft load = new Shaft(0, 0, 0, 8, 0.01, 0, 1);
		double weak = ClutchControl.torqueLimit(14);
		near(weak, ClutchCoupling.torque(motor, load, weak), 0,
			"insufficient pressure reaches its torque ceiling against a stalled load");
		near(0, step(0, weak, 0, 8, 0.01, 0), 0, "insufficient pressure cannot defeat static friction");
		Shaft fanDriver = new Shaft(10, 30, 1, 0, 0.0375, 0, 1);
		Shaft fan = new Shaft(10, 0, 0, 0, 0.01, 0.2, 1);
		near(20, ClutchCoupling.torque(fanDriver, fan, 32), 1e-7,
			"synchronous holding torque includes quadratic fan drag");
		// Keep the sides separate for this test: synchronization must come from
		// friction, without a topology merge hiding a persistent speed offset.
		double a = 0, b = 0, torque = 0;
		for (int i = 0; i < 1000; i++) {
			torque = ClutchCoupling.torque(new Shaft(a, 32, 1, 0, 0.0375, 0, 1),
				new Shaft(b, 0, 0, 8, 0.01, 0, 1), ClutchControl.torqueLimit(8));
			a = step(a, 32 - torque, 1, 0, 0.0375, 0);
			b = step(b, torque, 0, 8, 0.01, 0);
		}
		near(24, a, 1e-5, "geared driver converges to the loaded equilibrium");
		near(24, b, 1e-5, "low-inertia load converges without artificial slip");
		near(8, torque, 1e-5, "friction carries the load at equal shaft speeds");
	}

	private static double step(double w, double t, double k, double l, double j, double c) {
		return RotationMath.advance(w, t, k, l, j, c, RotationMath.TICK_MINUTES);
	}
	private static void near(double expected, double actual, double tolerance, String what) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
			throw new AssertionError(what + ": expected " + expected + ", got " + actual);
		checks++;
	}
}

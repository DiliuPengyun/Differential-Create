package com.diliupengyun.differential_create.kinetics;

/** Pure rotational mechanics, shared by the server, client and regression tests. */
public final class RotationMath {

	public static final double TICK_MINUTES = 1.0 / 1200;

	private RotationMath() {}

	/** J dw/dt = torque - load sign(w) - damping w - drag w |w|. */
	public static double advance(double speed, double torque, double damping, double load,
		double inertia, double drag, double minutes) {
		if (minutes <= 0)
			return speed;
		if (inertia <= 0) {
			if (damping == 0 && drag == 0)
				return Math.abs(torque - load * Math.signum(speed)) < 1e-6 ? speed : 0;
			return equilibrium(torque, damping, load, drag);
		}
		if (speed == 0 && Math.abs(torque) <= load)
			return 0;
		double sign = speed == 0 ? Math.signum(torque) : Math.signum(speed);
		double x = Math.abs(speed), a = sign * torque - load;
		if (a < 0) {
			double stop = timeToStop(x, a, damping, drag) * inertia;
			if (stop <= minutes) {
				if (Math.abs(torque) <= load)
					return 0;
				// Spend the rest of the tick accelerating in the opposite direction.
				return Math.signum(torque) * positiveStep(0, Math.abs(torque) - load,
					damping, drag, (minutes - stop) / inertia);
			}
		}
		return sign * Math.max(0, positiveStep(x, a, damping, drag, minutes / inertia));
	}

	public static double equilibrium(double torque, double damping, double load, double drag) {
		double a = Math.abs(torque) - load;
		if (a <= 0)
			return 0;
		if (drag > 0)
			return Math.signum(torque) * 2 * a / (damping + Math.sqrt(damping * damping + 4 * drag * a));
		return damping > 0 ? Math.signum(torque) * a / damping : Math.copySign(Double.POSITIVE_INFINITY, torque);
	}

	/** Exact Riccati solution on an interval that does not cross zero. */
	private static double positiveStep(double x, double a, double k, double c, double t) {
		if (c == 0)
			return k == 0 ? x + a * t : x + (a / k - x) * -Math.expm1(-k * t);
		double discriminant = k * k + 4 * c * a;
		if (discriminant > 0) {
			double d = Math.sqrt(discriminant);
			double root = 2 * a / (k + d);
			double decay = Math.exp(-d * t);
			return root + (x - root) * decay / (1 + c * (x - root) * -Math.expm1(-d * t) / d);
		}
		double y = x + k / (2 * c);
		if (discriminant == 0)
			return y / (1 + c * y * t) - k / (2 * c);
		double d = Math.sqrt(-discriminant);
		return (d * Math.tan(Math.atan(2 * c * y / d) - d * t / 2) - k) / (2 * c);
	}

	private static double timeToStop(double x, double a, double k, double c) {
		if (x == 0)
			return 0;
		if (c == 0)
			return k == 0 ? x / -a : Math.log1p(k * x / -a) / k;
		double discriminant = k * k + 4 * c * a;
		double y = 2 * c * x + k;
		if (discriminant > 0) {
			double d = Math.sqrt(discriminant);
			return Math.log1p(d * x * (k + d) / (-a * (y + d))) / d;
		}
		if (discriminant == 0)
			return 2 * (1 / k - 1 / y);
		double d = Math.sqrt(-discriminant);
		return 2 * Math.atan2(d * (y - k), d * d + y * k) / d;
	}

	/** q is the absorbed reference shaft's speed divided by the survivor's. */
	public static double mergedSpeed(double ja, double wa, double jb, double wb, double q) {
		double total = ja + jb * q * q;
		return total == 0 ? wa : (ja * wa + jb * q * wb) / total;
	}

	public static double mergeLoss(double ja, double wa, double jb, double wb, double q) {
		double referred = jb * q * q;
		double total = ja + referred;
		double delta = wa - wb / q;
		return total == 0 ? 0 : 0.5 * ja * referred / total * delta * delta;
	}
}

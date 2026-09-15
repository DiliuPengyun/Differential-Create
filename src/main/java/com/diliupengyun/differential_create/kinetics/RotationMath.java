package com.diliupengyun.differential_create.kinetics;

/** Pure rotational mechanics, shared by the server, client and regression tests. */
public final class RotationMath {

	public static final double TICK_MINUTES = 1.0 / 1200;

	private RotationMath() {}

	/** Shaft turns in the same calibrated units as torque and kinetic energy. */
	public record Motion(double speed, double angle, double travel) {}

	/** Integrate speed and travel together, including a stop or reversal inside the interval. */
	public static Motion motion(double speed, double torque, double damping, double load,
		double inertia, double drag, double minutes) {
		if (minutes <= 0) return new Motion(speed, 0, 0);
		if (inertia <= 0) {
			double next = advance(speed, torque, damping, load, inertia, drag, minutes);
			return new Motion(next, next * minutes, Math.abs(next) * minutes);
		}
		if (speed == 0 && Math.abs(torque) <= load) return new Motion(0, 0, 0);
		double sign = speed == 0 ? Math.signum(torque) : Math.signum(speed);
		double x = Math.abs(speed), a = sign * torque - load;
		double stop = a < 0 ? timeToStop(x, a, damping, drag) * inertia : Double.POSITIVE_INFINITY;
		double first = Math.min(minutes, stop);
		double travel = inertia * positiveTravel(x, a, damping, drag, first / inertia);
		if (stop <= minutes) {
			if (Math.abs(torque) <= load) return new Motion(0, sign * travel, travel);
			double rest = (minutes - stop) / inertia;
			double back = inertia * positiveTravel(0, Math.abs(torque) - load, damping, drag, rest);
			return new Motion(Math.signum(torque) * positiveStep(0, Math.abs(torque) - load, damping, drag, rest),
				sign * (travel - back), travel + back);
		}
		return new Motion(sign * Math.max(0, positiveStep(x, a, damping, drag, minutes / inertia)), sign * travel, travel);
	}

	private static double positiveTravel(double x, double a, double k, double c, double t) {
		if (t == 0) return 0;
		if (c == 0) {
			if (k == 0) return Math.max(0, t * (x + a * t / 2));
			double z = k * t;
			// (z + expm1(-z)) / z, without cancellation during very short steps.
			double f = Math.abs(z) < 1e-4 ? z * (0.5 + z * (-1.0 / 6 + z * (1.0 / 24 - z / 120)))
				: (z + Math.expm1(-z)) / z;
			return Math.max(0, t * (x + (a / k - x) * f));
		}
		double discriminant = k * k + 4 * c * a;
		double result;
		if (discriminant > 0) {
			double d = Math.sqrt(discriminant), root = 2 * a / (k + d);
			result = root * t + Math.log1p(c * (x - root) * -Math.expm1(-d * t) / d) / c;
		} else if (discriminant == 0) {
			result = Math.log1p(c * (x + k / (2 * c)) * t) / c - k * t / (2 * c);
		} else {
			double d = Math.sqrt(-discriminant), h = d * t / 2;
			result = Math.log1p(-2 * Math.pow(Math.sin(h / 2), 2) + (2 * c * x + k) / d * Math.sin(h)) / c
				- k * t / (2 * c);
		}
		return Math.max(0, result);
	}

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

package com.diliupengyun.differential_create.kinetics;

/** Exact voxel crossings of a translating point or a circular orbit, in shaft RPM-ticks. */
public final class ActorContactPath {
	public record Point(double x, double y, double z) {
		public double coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
	}
	private final Point centre, cosine, sine, velocity;
	private final double rate;
	private ActorContactPath(Point centre, Point cosine, Point sine, Point velocity, double rate) {
		this.centre = centre; this.cosine = cosine; this.sine = sine; this.velocity = velocity; this.rate = rate;
	}
	public static ActorContactPath linear(Point start, Point velocity) {
		return new ActorContactPath(start, new Point(0, 0, 0), new Point(0, 0, 0), velocity, 0);
	}
	public static ActorContactPath circular(Point centre, Point cosine, Point sine, double radiansPerTravel) {
		return new ActorContactPath(centre, cosine, sine, new Point(0, 0, 0), radiansPerTravel);
	}
	public Point at(double travel) {
		double c = Math.cos(rate * travel), s = Math.sin(rate * travel);
		return new Point(centre.x + cosine.x * c + sine.x * s + velocity.x * travel,
			centre.y + cosine.y * c + sine.y * s + velocity.y * travel,
			centre.z + cosine.z * c + sine.z * s + velocity.z * travel);
	}
	public Point derivative(double travel) {
		double c = Math.cos(rate * travel), s = Math.sin(rate * travel);
		return new Point(rate * (-cosine.x * s + sine.x * c) + velocity.x,
			rate * (-cosine.y * s + sine.y * c) + velocity.y,
			rate * (-cosine.z * s + sine.z * c) + velocity.z);
	}
	/** The forward-side voxel owns an exact boundary, in either direction. */
	public Point inside(double travel, double direction) {
		var p = at(travel); var v = derivative(travel);
		double length = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
		if (length == 0) return p;
		double n = direction * 1e-7 / length;
		return new Point(p.x + v.x * n, p.y + v.y * n, p.z + v.z * n);
	}
	public double exit(double travel, double direction) {
		var p = inside(travel, direction);
		double next = Double.POSITIVE_INFINITY;
		for (int axis = 0; axis < 3; axis++) {
			double low = Math.floor(p.coordinate(axis));
			if (rate == 0) {
				double v = velocity.coordinate(axis) * direction;
				if (v != 0) next = Math.min(next, ((v > 0 ? low + 1 : low) - at(travel).coordinate(axis)) / v);
			} else {
				double a = cosine.coordinate(axis), b = sine.coordinate(axis), radius = Math.hypot(a, b);
				if (radius == 0) continue;
				for (double plane : new double[]{low, low + 1}) {
					double h = (plane - centre.coordinate(axis)) / radius;
					// Tangency touches a face without entering its neighbour.
					if (Math.abs(h) >= 1) continue;
					double offset = Math.atan2(b, a), angle = Math.acos(h);
					for (double root : new double[]{offset - angle, offset + angle}) {
						double phase = direction * Math.signum(rate) * (root - rate * travel);
						phase = phase - Math.floor(phase / (2 * Math.PI)) * 2 * Math.PI;
						if (phase < 1e-10) phase += 2 * Math.PI;
						next = Math.min(next, phase / Math.abs(rate));
					}
				}
			}
		}
		return next > 1e-6 ? next : 1e-6;
	}
}

package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.kinetics.ActorContactPath;
import com.diliupengyun.differential_create.kinetics.ActorContactPath.Point;
import com.diliupengyun.differential_create.kinetics.RotationMath;

final class ActorContactRegression {
	private static int checks;
	static void run() {
		var line = ActorContactPath.linear(new Point(.25, .5, .5), new Point(1.0 / 512, 0, 0));
		near(384, line.exit(0, 1), 1e-10, "forward face distance");
		near(128, line.exit(0, -1), 1e-10, "reverse face distance");
		near(512, line.exit(384, 1), 1e-10, "forward boundary belongs to the next voxel");
		near(512, line.exit(-128, -1), 1e-10, "reverse boundary belongs to the next voxel");
		var still = ActorContactPath.linear(new Point(.5, .5, .5), new Point(0, 0, 0));
		if (Double.isFinite(still.exit(0, 1))) throw new AssertionError("stationary tool has no voxel crossings");
		double rate = Math.PI / 600;
		var circle = ActorContactPath.circular(new Point(.5, .5, .5), new Point(0, 4, 0), new Point(0, 0, 4), rate);
		near(Math.asin(.125) / rate, circle.exit(0, 1), 1e-10, "first circular face");
		near(Math.asin(.125) / rate, circle.exit(0, -1), 1e-10, "symmetric reverse circular face");
		for (int sign : new int[]{-1, 1}) {
			double travel = 0;
			int count = 0;
			while (Math.abs(travel) < 1200) {
				var before = circle.inside(travel, sign);
				double exit = circle.exit(travel, sign);
				if (!(exit > 0) || !Double.isFinite(exit)) throw new AssertionError("circular sweep must progress");
				var middle = circle.at(travel + sign * exit * .5);
				near(Math.floor(before.y()), Math.floor(middle.y()), 0, "no skipped y cell");
				near(Math.floor(before.z()), Math.floor(middle.z()), 0, "no skipped z cell");
				travel += sign * exit;
				if (++count > 100) throw new AssertionError("sweep did not converge");
			}
			if (count < 20) throw new AssertionError("complete high-speed orbit must visit its intermediate cells");
		}
		// Stone costs 4 * 1500 / 1200 = 5 calibrated energy units in a .25-block stroke.
		var cut = RotationMath.motion(64, 0, 0, 4 * 1500.0 / 128, .01, 0, 1.0 / 1200);
		near(4 * 1500.0 / 128 * cut.travel(), .5 * .01 * (64 * 64 - cut.speed() * cut.speed()), 1e-12, "cutting energy comes from real motion");
		near(0, RotationMath.motion(0, 40, 0, 4 * 1500.0 / 128, .01, 0, 1.0 / 1200).travel(), 0, "insufficient drive torque cannot cut at rest");
		System.out.println("Actor contact regression: " + checks + " checks passed");
	}
	private static void near(double expected, double actual, double tolerance, String label) {
		checks++;
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance)
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}

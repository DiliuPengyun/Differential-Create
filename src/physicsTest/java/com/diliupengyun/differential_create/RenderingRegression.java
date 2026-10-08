package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.client.PortRotation;
import com.diliupengyun.differential_create.client.RotationFrame;
import com.diliupengyun.differential_create.client.InstanceRotation;
import com.diliupengyun.differential_create.client.FreeShaftRotation;
import com.diliupengyun.differential_create.client.ClutchAnimationCache;
import com.diliupengyun.differential_create.client.RotorAnimation;
import com.diliupengyun.differential_create.client.BearingAnimation;
import com.diliupengyun.differential_create.client.ShaftModelOffset;
import com.diliupengyun.differential_create.client.PipeFluidGeometry;
import com.diliupengyun.differential_create.kinetics.ShaftPhases;
import com.diliupengyun.differential_create.kinetics.TorsionSolver;
import com.diliupengyun.differential_create.kinetics.BearingAngle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

/** Exercises the production animation math without a GPU or a Minecraft client. */
public final class RenderingRegression {
	private static int checks;

	public static void run() {
		assemblyPhases();
		largeCogOffsets();
		clutchCogShaftAlignment();
		freeClutchOutput();
		clutchAnimationCache();
		rotorSamples();
		bearingPoses();
		bearingSampleBuffer();
		fallingWaterGeometry();
		horizontalWaterGeometry();
		reducedDriveOscillation();
		PortRotation wheel = new PortRotation();
		PortRotation shaft = new PortRotation();
		wheel.resolve(new RotationFrame(0, 40, 2.4), 8, 8, 0);
		shaft.resolve(new RotationFrame(0, -70, 1.2), 4, 4, 0);
		RotationFrame merged = new RotationFrame(100, 120, 4.8);
		RotationFrame wheelFrame = wheel.resolve(merged, 16, 16, 100.25);
		RotationFrame shaftFrame = shaft.resolve(merged, 16, 16, 100.75);
		RotationFrame rebuilt = new PortRotation().resolve(merged, 16, 16, 100.5);
		for (float tick : new float[]{100, 100.25f, 100.75f, 115.5f}) {
			double expected = 142.5 + (tick - 100) * 4.8;
			angle(expected, shader(wheelFrame, tick, 22.5f), "wheel aligns after independently spinning networks merge");
			angle(expected, shader(shaftFrame, tick, 22.5f), "shaft update time does not retain its old phase");
			angle(expected, shader(rebuilt, tick, 22.5f), "new or rebuilt instance aligns with existing instances");
		}

		// A 0 -> 16 RPM tick turns through 2.4 degrees. Both render paths
		// must traverse the same segment, rather than use the endpoint speed.
		RotationFrame ramp = new RotationFrame(3500, 100, 2.4);
		near(101.2, ramp.angleAt(3500.5), 1e-9, "partial tick interpolates the integrated angle");
		angle(101.2, shader(ramp, 3500.5f, 0), "GPU and ordinary renderer agree during acceleration");
		angle(72.5, shader(ramp.scaled(0.5), 3500, 22.5f), "gear ratio and static model offset both survive");
		angle(-50.6, shader(ramp.scaled(-0.5), 3500.5f, 0), "negative gear ratio reverses the phase");

		RotationFrame steady = new RotationFrame(100, 120, 4.8);
		check(steady.sameMotion(new RotationFrame(101, 124.8, 4.8)), "constant-speed networks can reuse shader coefficients");
		check(!steady.sameMotion(new RotationFrame(101, 124.8, 4.799)), "final sub-threshold speed change still refreshes members");
		check(!steady.sameMotion(new RotationFrame(101, 130, 4.8)), "topology-only phase change refreshes members");
		RotationFrame changedBasis = steady.scaled(-2).scaled(-0.5);
		near(steady.angleAt(115.5), changedBasis.angleAt(115.5), 1e-9, "network basis change preserves the surviving shaft phase");

		PortRotation reversePort = new PortRotation();
		reversePort.resolve(steady, 16, -16, 100);
		RotationFrame stop = reversePort.resolve(new RotationFrame(101, 124.8, 0), 0, 0, 101);
		angle(-124.8, shader(stop, 102, 0), "stopping does not lose a gearbox port's direction");

		PortRotation stoppedPort = new PortRotation();
		stoppedPort.resolve(steady, 16, 16, 100);
		RotationFrame detached = stoppedPort.resolve(steady, 16, 0, 100.5);
		angle(122.4, shader(detached, 100.75f, 0), "generic zero-speed port freezes at its visible angle");
		RotationFrame stillDetached = stoppedPort.resolve(new RotationFrame(102, 129.6, 4.8), 16, 0, 102);
		angle(122.4, shader(stillDetached, 110, 0), "generic zero-speed port stays still while the input turns");
		RotationFrame rejoined = stoppedPort.resolve(new RotationFrame(110, 168, 4.8), 16, 16, 110);
		angle(169.2, shader(rejoined, 110.25f, 0), "reconnected port rejoins the shared phase");

		RotationFrame frozen = new RotationFrame(101, 123.6, 0);
		angle(123.6, shader(frozen, 101.9f, 0), "tick freeze prevents partial-tick motion");
		RotationFrame resumed = new RotationFrame(102, 123.6, 4.8);
		angle(123.6, shader(resumed, 102, 0), "resume starts at the frozen phase");
		System.out.println("Rendering regression: " + checks + " checks passed");
	}

	private static void fallingWaterGeometry() {
		for (double fill : new double[]{0, 1e-12, .001, .14, .5, .817, .999999, 1}) {
			for (double[] flow : new double[][]{{0, 0}, {20, 0}, {0, 20}, {20, 20}, {20, 3.5}, {3.5, 20},
				{1e-8, 1e-8}, {-20, -20}, {Double.NaN, 20}, {20, Double.POSITIVE_INFINITY}}) {
				var column = PipeFluidGeometry.vertical(fill, flow[0], flow[1]);
				check(Double.isFinite(column.poolHeight()) && column.poolHeight() >= 0 && column.poolHeight() <= 1,
					"a rendered pool remains inside the vertical pipe");
				check(Double.isFinite(column.streamRadius()) && column.streamRadius() >= 0 && column.streamRadius() <= 1,
					"a falling stream fits the bore even at small inventories and invalid flow samples");
				double prismVolume = column.poolHeight() + column.streamRadius() * column.streamRadius() * (1 - column.poolHeight());
				near(fill, prismVolume, 2e-15, "pool and stream contain the same visual volume as the original interval");
			}
			var staticColumn = PipeFluidGeometry.vertical(fill, 20, 0);
			near(fill, staticColumn.poolHeight(), 0, "a blocked outlet displays the actual static water level");
			near(0, staticColumn.streamRadius(), 0, "a blocked outlet does not fabricate a flowing column");
		}
		var falling = PipeFluidGeometry.vertical(.14, 20, 20);
		near(0, falling.poolHeight(), 0, "steady falling water is not stacked at each block's bottom");
		check(falling.streamRadius() > 0 && falling.streamRadius() < 1, "a partly filled flowing cell displays a narrow stream reaching both faces");
		var blocked = PipeFluidGeometry.vertical(.14, 20, .001);
		check(blocked.poolHeight() > .139, "a strongly restricted outlet restores the pool continuously");
		check(PipeFluidGeometry.vertical(0, 20, 20).streamRadius() == 0, "dry pipes remain visually dry despite stale flow");
	}

	private static void horizontalWaterGeometry() {
		for (double fill : new double[]{0, 1e-12, .001, .25, .5931183307694449, .999999, 1}) {
			var layer = PipeFluidGeometry.horizontal(fill);
			near(5 / 16d, layer.bottom(), 0, "horizontal liquid touches the bore's bottom at either end");
			check(layer.top() >= layer.bottom() && layer.top() <= 11 / 16d, "horizontal liquid stays inside the bore");
			// Integrate a full-length box independently of the former plug length.
			double boxVolume = (layer.top() - layer.bottom()) * (6 / 16d);
			near(fill * (6 / 16d) * (6 / 16d), boxVolume, 3e-17, "a bottom layer preserves the former plug's display volume");
			double center = .5 + layer.centerOffset(), radius = layer.height() * 3 / 16d;
			near(layer.bottom(), center - radius, 2 * Math.ulp(layer.bottom()), "world-Y instance scaling keeps its lower wall fixed");
			near(layer.top(), center + radius, 2 * Math.ulp(layer.top()), "instanced and ordinary paths produce the same water surface");
		}
		for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1})
			near(0, PipeFluidGeometry.horizontal(invalid).height(), 0, "invalid or negative samples do not manufacture visible water");
		near(1, PipeFluidGeometry.horizontal(2).height(), 0, "an over-capacity display cannot extend outside the pipe");
		double previous = 5 / 16d;
		for (int i = 0; i <= 256; i++) {
			double top = PipeFluidGeometry.horizontal(i / 256d).top();
			check(top >= previous, "the horizontal water level rises monotonically as inventory increases");
			previous = top;
		}
	}

	private static void bearingPoses() {
		var server = new BearingAngle();
		server.align(350);
		server.advance(10, 20);
		near(370, server.withPending(10, 0), 0, "server retains forward turns across the native wrap");
		server.advance(350, -20);
		near(350, server.withPending(350, 0), 0, "server retains a real reversal");
		server.advance(170, 900);
		near(1250, server.withPending(170, 0), 0, "native whole turns survive a high-speed tick");
		server.advance(-10, -1260);
		near(-10, server.withPending(-10, 0), 0, "reverse whole turns survive a high-speed tick");
		server.advance(5, 15);
		near(5, server.withPending(5, 0), 0, "a sequence limit uses applied travel rather than shaft speed");
		server.advance(5, 0);
		near(5, server.withPending(5, 0), 0, "a stalled structure stays still while the shaft can turn");
		server.align(147.90634155273438);
		double paid = 5.751013325853354;
		near(153.65735487858774, server.withPending(147.90634155273438, paid), 1e-12,
			"zero endpoint RPM still includes the final paid contact motion");
		near(153.65735487858774, server.withPending(147.90634155273438, paid), 1e-12,
			"repeated snapshots neither consume nor duplicate pending motion");
		server.advance(153.6573486328125, (float) paid);
		near(153.6573486328125, server.withPending(153.6573486328125, 0), 1e-12,
			"the next native tick consumes that same movement once");

		var client = new BearingAnimation();
		client.receive(17, 147.90634155273438);
		client.advance(100, 100, false);
		client.receive(17, 153.65735487858774);
		client.advance(101, 101, false);
		near(147.90634155273438, client.frame(17).angle(), 0, "startup waits for the one-sample reserve");
		client.receive(17, 153.65735487858774);
		client.advance(102, 102, false);
		RotationFrame contact = client.frame(17);
		for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
			near(147.90634155273438 + paid * partial, BearingAnimation.angleAt(contact, partial), 1e-5,
				"the stopped drill completes its contact segment at every render fraction");
		}
		client.advance(103, 103, false);
		near(0, client.frame(17).degreesPerTick(), 0, "no endpoint-RPM prediction or lazy angle correction follows the stop");
		client.receive(17, 147.90634155273438);
		client.advance(104, 104, false);
		check(client.frame(17).degreesPerTick() < 0, "a genuine backward physical sample remains visible");
		client.receive(17, 867.90634155273438);
		client.advance(105, 105, false);
		near(720, client.frame(17).degreesPerTick(), 1e-10, "two full turns are not replaced with the shortest arc");
		near(327.90634155273438, BearingAnimation.angleAt(client.frame(17), .25f), 1e-5,
			"high-speed partial-tick pose is reduced only after interpolation");
		client.advance(106, 105.25, true);
		near(0, client.frame(17).degreesPerTick(), 0, "tick freeze stops the contraption at its visible pose");
		client.receive(18, 0);
		check(client.frame(17) == null && client.frame(18) == null, "reassembly cannot reuse the previous entity's animation");
		client.advance(107, 107, false);
		near(0, client.frame(18).angle(), 0, "a newly assembled structure has its own zero angle");
		client.receive(18, 1e12 + .125);
		client.advance(108, 108, false);
		client.receive(18, 1e12 + .125);
		client.advance(109, 109, false);
		near((1e12 + .125) % 360, BearingAnimation.angleAt(client.frame(18), 1), 1e-5,
			"large accumulated angles are reduced before conversion to float");
		client.receive(18, 1e12 + .125);
		client.advance(110, 110, false);
		near(0, client.frame(18).degreesPerTick(), 0, "a held snapshot keeps the rebuilt animation stationary");
	}

	private static void bearingSampleBuffer() {
		// Alternating 0/2 arrivals reproduced the reported 0/38.4-degree ticks
		// at a steady 64 RPM. Server history must instead play one tick at a time.
		for (int scenario = 0; scenario < 4; scenario++) {
			double[] poses = new double[401];
			poses[0] = 350;
			for (int sample = 1; sample < poses.length; sample++) {
				double travel = switch (scenario) {
					case 0 -> 19.2;
					case 1 -> -19.2;
					case 2 -> 720;
					default -> sample < 61 ? 19.2 : sample == 61 ? 5.751013325853354
						: sample <= 100 ? 0 : sample <= 200 ? -19.2 : 0;
				};
				poses[sample] = poses[sample - 1] + travel;
			}
			var client = new BearingAnimation();
			client.receive(7, poses[0]); client.advance(0, 0, false);
			client.receive(7, poses[1]); client.advance(1, 1, false);
			near(0, client.frame(7).degreesPerTick(), 0, "one confirmed interval preloads the jitter reserve");
			for (int tick = 2; tick <= 400; tick++) {
				if (tick == 2) client.receive(7, poses[2]);
				else if ((tick & 1) == 0) {
					client.receive(7, poses[tick - 1]); client.receive(7, poses[tick]);
				}
				client.advance(tick, tick, false);
				var frame = client.frame(7);
				double previous = poses[tick - 2], next = poses[tick - 1];
				near(previous, frame.angle(), 1e-10, "arrival batching cannot skip a confirmed physical pose");
				near(next, frame.angleAt(tick + 1), 1e-10, "arrival batching cannot double or erase an interval");
				for (float partial : new float[]{0, .13f, .5f, .9f, 1}) {
					double visible = frame.angleAt(tick + (double) partial);
					check(visible >= Math.min(previous, next) - 1e-10 && visible <= Math.max(previous, next) + 1e-10,
						"buffered render fractions stay between confirmed endpoints at stops and reversals");
				}
			}
			client.advance(401, 401, false);
			near(poses[400], client.frame(7).angleAt(402), 1e-10, "the reserve drains to the exact final contact pose");
			for (int tick = 402; tick < 412; tick++) {
				client.advance(tick, tick, false);
				near(0, client.frame(7).degreesPerTick(), 0, "a genuine prolonged packet gap never extrapolates motion");
			}
		}
		var frozen = new BearingAnimation();
		frozen.receive(9, 0); frozen.advance(0, 0, false);
		frozen.receive(9, 10); frozen.receive(9, 20); frozen.advance(1, 1, false);
		frozen.advance(2, 1.5, true);
		near(5, frozen.frame(9).angle(), 0, "freezing a buffered segment preserves its displayed partial pose");
		frozen.receive(9, 30); frozen.advance(3, 3, true);
		near(5, frozen.frame(9).angle(), 0, "queued poses cannot move a frozen bearing");
		frozen.advance(4, 4, false);
		near(10, frozen.frame(9).angleAt(5), 0, "resume finishes the interrupted segment before its successor");
		frozen.advance(5, 5, false);
		near(20, frozen.frame(9).angleAt(6), 0, "freeze preserves the next queued server sample");
		frozen.receive(10, -20); frozen.advance(6, 6, false);
		check(frozen.frame(9) == null, "entity replacement drops the previous assembly's queued poses");
		near(-20, frozen.frame(10).angle(), 0, "the replacement starts at its own confirmed pose");
		var stalled = new BearingAnimation();
		stalled.receive(2, 0); stalled.advance(0, 0, false);
		for (int sample = 1; sample <= 10000; sample++) stalled.receive(2, sample * 720.0);
		stalled.advance(1, 1, false);
		near(9999 * 720.0, stalled.frame(2).angleAt(2), 0, "a long render stall restores bounded display delay");
		stalled.advance(2, 2, false);
		near(10000 * 720.0, stalled.frame(2).angleAt(3), 0, "burst recovery still reaches the latest authoritative endpoint");
		stalled.advance(3, 3, false);
		near(0, stalled.frame(2).degreesPerTick(), 0, "burst recovery does not invent further movement");
	}

	private static void clutchAnimationCache() {
		var cache = new ClutchAnimationCache<Object>();
		check(cache.snapshot(key -> false).isEmpty(), "an empty animation cache has no tick work");
		Object first = new Object(), second = new Object();
		FreeShaftRotation firstRotation = cache.get(first), secondRotation = cache.get(second);
		firstRotation.resolve(new RotationFrame(10, 123, 4.8), 16, 10, 10, false);
		secondRotation.resolve(new RotationFrame(10, -40, -2.4), -8, 10, 10, false);
		check(cache.get(first) == firstRotation, "rebuilding a visual reuses its existing free-port phase");
		var snapshot = cache.snapshot(key -> false);
		check(snapshot.size() == 2, "the tick starts with both live clutch outputs");
		var additions = new ArrayList<Object>();
		for (int i = 0; i < 64; i++) additions.add(new Object());
		// Complete worker insertions while the tick is still inside its callback.
		// Iterating the original WeakHashMap throws ConcurrentModificationException here.
		try (var worker = Executors.newSingleThreadExecutor()) {
			int[] visited = {0};
			snapshot.forEach((key, rotation) -> {
				await(worker.submit(() -> { for (Object added : additions) cache.get(added); return null; }));
				check(key == first || key == second, "new visuals wait until the next tick snapshot");
				check(rotation == cache.get(key), "a worker cannot replace a live output's phase state");
				visited[0]++;
			});
			check(visited[0] == 2, "cache growth never skips or repeats the original tick members");
			var next = cache.snapshot(key -> false);
			check(next.size() == 66, "the next tick includes every output created by a worker");
			near(123, next.get(first).frame().angleAt(10), 0, "snapshot copying preserves the first output angle");
			near(-40, next.get(second).frame().angleAt(10), 0, "snapshot copying preserves the second output angle");
			cache.get(additions.getFirst()).resolve(new RotationFrame(20, 70, 1.2), 4, 20, 20, false);
			var retained = cache.snapshot(key -> key != additions.getFirst());
			check(retained.size() == 1, "removed clutches and other worlds release their animation entries");
			near(70, retained.get(additions.getFirst()).frame().angleAt(20), 0, "world cleanup preserves a surviving output's phase");
			check(snapshot.size() == 2 && snapshot.get(first) == firstRotation, "cleanup cannot invalidate a tick already in progress");
			var cleared = cache.snapshot(key -> true);
			check(cleared.isEmpty(), "leaving the world clears all cached outputs");
			check(cache.get(first) != firstRotation, "a newly loaded clutch gets a fresh phase after cleanup");
		}
		// All render workers must publish the same state even on first use.
		var parallel = new ClutchAnimationCache<Object>();
		try (var workers = Executors.newFixedThreadPool(4)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var states = new ArrayList<Future<FreeShaftRotation>>();
			for (int i = 0; i < 4; i++) states.add(workers.submit(() -> { start.await(); return parallel.get(first); }));
			start.countDown();
			FreeShaftRotation shared = await(states.getFirst());
			for (var state : states) check(await(state) == shared, "simultaneous visual creation installs one phase state");
			check(parallel.snapshot(key -> false).size() == 1, "simultaneous first use never duplicates a clutch entry");
		}
	}

	private static <T> T await(Future<T> result) {
		try { return result.get(5, TimeUnit.SECONDS); }
		catch (Exception e) { throw new AssertionError("animation cache worker failed or remained blocked", e); }
	}

	private static void rotorSamples() {
		RotorAnimation rotor = new RotorAnimation();
		check(rotor.advance(100, 100, false) == null, "unsynced animation leaves the renderer fallback available");
		rotor.receive(359.99);
		RotationFrame first = rotor.advance(100, 100, false);
		near(359.99, first.angleAt(100.75), 0, "first sample has no invented motion");
		rotor.receive(360.05);
		RotationFrame forward = rotor.advance(101, 101, false);
		near(.06, forward.degreesPerTick(), 1e-12, "unwrapped samples preserve forward motion across 360 degrees");
		near(360.02, forward.angleAt(101.5), 1e-12, "partial tick interpolates authoritative endpoints");
		RotationFrame missing = rotor.advance(102, 102, false);
		near(360.05, missing.angleAt(110.5), 0, "missing packets hold the known endpoint without extrapolation");
		rotor.receive(360.03);
		rotor.receive(359.99);
		RotationFrame reverse = rotor.advance(103, 103, false);
		near(360.05, reverse.angleAt(103), 0, "coalesced packets start at the preceding visible endpoint");
		near(359.99, reverse.angleAt(104), 0, "coalesced packets finish at the latest server sample");
		near(360.02, reverse.angleAt(103.5), 1e-12, "signed angle samples preserve reversal");
		RotationFrame frozen = rotor.advance(104, 103.25, true);
		near(360.035, frozen.angleAt(115), 1e-12, "tick freeze holds the currently visible partial angle");
		rotor.receive(360.08);
		RotationFrame stillFrozen = rotor.advance(105, 105.8, true);
		near(360.035, stillFrozen.angleAt(120), 1e-12, "pending packets do not animate a frozen rotor");
		RotationFrame resumed = rotor.advance(106, 106, false);
		near(360.035, resumed.angleAt(106), 1e-12, "resume preserves the frozen phase");
		near(360.08, resumed.angleAt(107), 0, "resume finishes at the latest authoritative endpoint");
		rotor.receive(1080.08);
		RotationFrame fast = rotor.advance(107, 107, false);
		near(720, fast.degreesPerTick(), 1e-12, "several rotations per tick are not reduced to the shortest arc");
		near(1080.08, fast.angleAt(108), 0, "high-speed interpolation retains complete revolutions");
		for (RotationFrame sample : new RotationFrame[]{first, forward, missing, reverse, frozen, resumed, fast}) {
			for (float partial : new float[]{0, .25f, .5f, .75f, 1})
				angle(sample.angleAt(sample.tick() + partial) + 11.25,
					shader(sample, sample.tick() + partial, 11.25f),
					"ordinary and instanced rendering share the received angle segment");
		}
	}

	/** The reported two-stage 1/16 reduction has a high-frequency, small-angle elastic mode. */
	private static void reducedDriveOscillation() {
		var bodies = new ArrayList<TorsionSolver.Body>();
		for (int i = 0; i < 11; i++)
			bodies.add(new TorsionSolver.Body(i == 0 || i == 2 ? (double) .01f : 0,
				i == 0 ? 32768 : 0, i == 0 ? 16384 : 0, 0, 0));
		int[][] ports = {{0, 7}, {1, 2}, {3, 5}, {3, 4}, {4, 6}, {5, 8}, {6, 1}, {7, 9}, {8, 10}, {9, 10}};
		double[] ratios = {1, 1, -1, 1, 1.0 / 16, -1, -1, 1.0 / 16, -1, 1};
		var links = new ArrayList<TorsionSolver.Link>();
		for (int e = 0; e < ports.length; e++)
			links.add(new TorsionSolver.Link(ports[e][0], ports[e][1], ratios[e], 2e7 / (1 + ratios[e] * ratios[e]),
				0, Math.min(8192 / Math.abs(ratios[e]), 8192), Double.POSITIVE_INFINITY));
		double[] speed = {1.9999652869344637, -1.7375142200550253, -2.8831472788430172,
			.19763101070719102, .2692330768814841, -.12602894453297406, .5918811612670316,
			1.9954901577985724, .05442687835869828, .08877725398979122, .01717518781553133};
		double[] twist = {-6.98551008740819e-7, 7.486348391715963e-5, 6.237681614106457e-5,
			6.237681614193593e-5, 8.97638046244371e-5, -6.237681614182765e-5, 7.486348391713822e-5,
			-3.1310237790654006e-5, 6.237681614110938e-5, -6.237681614168138e-5};
		RotorAnimation cog = new RotorAnimation();
		double theta = 100, maxMovement = 0, maxEndpointPrediction = 0;
		cog.receive(theta); cog.advance(5900, 5900, false);
		InstanceRotation teeth = new InstanceRotation(), shaft = new InstanceRotation();
		for (int tick = 5901; tick <= 6028; tick++) {
			var result = TorsionSolver.advance(bodies, links, speed, twist, 1.0 / 1200, 1.0 / 1200 / 64);
			check(result.broken() < 0, "the animation fixture preserves its unbroken physical transmission");
			speed = result.speed(); twist = result.twist();
			double previous = theta, movement = result.angle()[1] * 360;
			theta += movement;
			maxMovement = Math.max(maxMovement, Math.abs(movement));
			maxEndpointPrediction = Math.max(maxEndpointPrediction, Math.abs(speed[1] * .3));
			cog.receive(theta);
			RotationFrame frame = cog.advance(tick, tick, false);
			near(previous, frame.angleAt(tick), 1e-12, "each physical sample starts at the preceding angle without snapping");
			near(theta, frame.angleAt(tick + 1), 1e-12, "the animation ends at the actual integrated angle");
			near(movement, frame.degreesPerTick(), 1e-12, "visible travel uses the solver integral independently of endpoint RPM");
			float toothOffset = setup(teeth, frame, 11.25f);
			setup(shaft, frame, 11.25f);
			float shaftOffset = shaft.setModelOffset(0);
			for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
				double expected = previous + movement * partial;
				double actual = frame.angleAt(tick + partial);
				check(actual >= Math.min(previous, theta) - 1e-12 && actual <= Math.max(previous, theta) + 1e-12,
					"high-frequency endpoint speeds cannot push the visible angle beyond server samples");
				near(expected, actual, 1e-12, "ordinary rendering follows the small signed physical movement");
				float animated = (tick + partial) / 20f * frame.shaderSpeed();
				angle(expected + 11.25, toothOffset + animated, "Flywheel large-cog teeth follow the same integrated segment");
				angle(expected, shaftOffset + animated, "Flywheel large-cog shaft retains its phase during oscillation");
			}
		}
		check(maxMovement < .07, "the real low-speed oscillation moves less than .07 degrees in a tick");
		check(maxEndpointPrediction > .5, "the fixture reproduces the old oversized endpoint-speed prediction");
	}

	private static void assemblyPhases() {
		// A spinning flywheel existed before the shaft. Their arbitrary angle
		// origins must be reconciled even if they already have the same RPM.
		double[] oldAngles = {17.1375, 0, -41.8, -2.25, 300};
		var contacts = List.of(new ShaftPhases.Contact(0, 1, 1, 0),
			new ShaftPhases.Contact(1, 2, -2, .0001), new ShaftPhases.Contact(3, 4, 1, 0));
		double[] aligned = ShaftPhases.align(oldAngles, contacts);
		near(oldAngles[0], aligned[0], 0, "existing flywheel keeps its phase when a shaft is placed");
		near(aligned[0], aligned[1], 0, "new shaft shares the flywheel assembly zero");
		near(.0001, -2 * aligned[1] - aligned[2], 1e-12, "geared contact retains physical spring twist");
		near(oldAngles[3], aligned[3], 0, "opposite clutch half retains its independent phase");
		near(aligned[3], aligned[4], 0, "output shafts align with their own clutch half");
		near(0, oldAngles[1], 0, "assembly calculation does not overwrite its input");
		double[] rebuilt = ShaftPhases.align(aligned, contacts);
		for (int i = 0; i < aligned.length; i++)
			near(aligned[i], rebuilt[i], 1e-12, "unchanged topology does not move the visible phase");
		var reversed = ShaftPhases.align(new double[]{aligned[0], -100},
			List.of(new ShaftPhases.Contact(1, 0, -.5, .00005)));
		near(-2 * aligned[0] - .0001, reversed[1], 1e-12, "traversing a gear contact backwards preserves sign and preload");
		var split = ShaftPhases.align(aligned, List.of(new ShaftPhases.Contact(0, 1, 1, 0)));
		near(aligned[2], split[2], 0, "detached component retains its last angle");
		for (float tick : new float[]{100, 100.25f, 101}) {
			var wheel = new RotationFrame(100, aligned[0] * 360, 4.8);
			var shaft = new RotationFrame(100, aligned[1] * 360, 4.8);
			angle(wheel.angleAt(tick) + 22.5, shader(shaft, tick, 22.5f),
				"ordinary wheel rendering and instanced shaft rendering agree after hot placement");
		}
	}

	private static void freeClutchOutput() {
		for (int sign : new int[]{-1, 1}) {
			FreeShaftRotation output = new FreeShaftRotation();
			RotationFrame input = new RotationFrame(100, sign * 120, sign * 4.8);
			output.resolve(input, sign * 16, 100, 100, false);
			RotationFrame released = output.resolve(null, sign * 16, 100, 100.5, false);
			angle(sign * 123.6, shader(released, 100.75f, 0), "bare clutch output keeps turning immediately after release");
			near(input.angleAt(100.5), released.angleAt(100.5), 1e-9, "release has no angle jump");
			RotationFrame coast = output.resolve(null, sign * 16, 110, 110.25, false);
			angle(sign * 169.2, shader(coast, 110.25f, 0), "free output retains its own motion across client ticks");
			float rebuiltOffset = setup(new InstanceRotation(), coast, 22.5f);
			angle(sign * 169.2 + 22.5, rebuiltOffset + 110.25f / 20f * coast.shaderSpeed(),
				"rebuilding an instance uses the existing port phase");
			RotationFrame corrected = output.resolve(null, sign * 8, 110, 110.25, false);
			near(sign * 169.2, corrected.angleAt(110.25), 1e-9, "server speed correction preserves the visible angle");
			angle(sign * 171.6, shader(corrected, 111.25f, 0), "both rendering paths use corrected output RPM");
			double frozenAngle = corrected.angleAt(111.5);
			RotationFrame frozen = output.resolve(null, sign * 8, 111, 111.5, true);
			angle(frozenAngle, shader(frozen, 119.75f, 0), "tick freeze also stops a networkless output");
			RotationFrame resumed = output.resolve(null, sign * 8, 120, 120.5, false);
			near(frozenAngle, resumed.angleAt(120.5), 1e-9, "resume does not count frozen time as rotation");
			angle(frozenAngle + sign * 2.4, shader(resumed, 121.5f, 0), "free output resumes its stored RPM");
			RotationFrame driven = new RotationFrame(122, -70, 1.2);
			RotationFrame connected = output.resolve(driven, sign * 8, 122, 122.5, false);
			near(driven.angleAt(122.5), connected.angleAt(122.5), 0, "attached output follows its actual network");
			RotationFrame locked = output.resolve(input, sign * 8, 123, 123.25, false);
			near(input.angleAt(123.25), locked.angleAt(123.25), 0, "bare re-engagement follows the input phase");
		}
		RotationFrame lateJoin = new FreeShaftRotation().resolve(null, 24, 500, 500, false);
		near(7.2, lateJoin.angleAt(501), 1e-9, "newly tracked open clutch animates its saved output speed");
	}

	private static void largeCogOffsets() {
		InstanceRotation cog = new InstanceRotation();
		InstanceRotation shaft = new InstanceRotation();
		InstanceRotation encasedShaft = new InstanceRotation();
		RotationFrame[] frames = {
			new RotationFrame(6000, 123, -2.4),
			new RotationFrame(6001, 120.6, -2.6),
			new RotationFrame(6002, 118, -2.8),
			new RotationFrame(6003, 115.2, 0),
			new RotationFrame(6004, 115.2, 1.1)
		};
		for (int i = 0; i < frames.length; i++) {
			RotationFrame frame = frames[i];
			float cogOffset = setup(cog, frame, 11.25f);
			setup(shaft, frame, 11.25f);
			// LargeCogVisual calls this AFTER setup, in construction and update.
			float shaftOffset = shaft.setModelOffset(0);
			float encasedOffset = setup(encasedShaft, frame, 11.25f);
			// EncasedCogVisual supplies its shaft override only at construction.
			if (i == 0) encasedOffset = encasedShaft.setModelOffset(0);
			for (float tick : new float[]{frame.tick(), frame.tick() + 0.5f}) {
				double expected = frame.angleAt(tick);
				float animated = tick / 20f * frame.shaderSpeed();
				angle(expected + 11.25, cogOffset + animated, "large cog retains its tooth alignment");
				angle(expected, shaftOffset + animated, "late shaft alignment does not erase accumulated motion");
				angle(expected, encasedOffset + animated, "encased shaft alignment survives later default setup");
			}
		}
		// Until network data arrives, the same setter path must retain Create's
		// static offset without reusing an obsolete animation compensation.
		near(0, setup(encasedShaft, null, 11.25f), 0, "unsynced shaft uses only its static model alignment");
		near(22.5, new InstanceRotation().setModelOffset(22.5f), 0, "instances without network setup keep vanilla offsets");
	}

	private static void clutchCogShaftAlignment() {
		// At the reported Y-axis position (x=7, z=22), the cog teeth use
		// 11.25 degrees but its centre shaft and the clutch's mating shaft use 0.
		// Even positions use 22.5 degrees for both ordinary and encased cogs.
		for (boolean offsetPosition : new boolean[]{false, true}) {
			float expectedShaftOffset = offsetPosition ? 22.5f : 0;
			float toothOffset = offsetPosition ? 22.5f : 11.25f;
			float shaftOffset = ShaftModelOffset.of(true, offsetPosition, 0);
			near(expectedShaftOffset, shaftOffset, 0, "clutch uses the large cog's centre-shaft alignment");
			// A custom body offset is also absent from Create's large-cog shaft.
			near(expectedShaftOffset, ShaftModelOffset.of(true, offsetPosition, 15), 0,
				"large-cog body offsets do not reach its mating shaft");
			near(expectedShaftOffset + 15, ShaftModelOffset.of(false, offsetPosition, 15), 0,
				"other machines retain their custom shaft alignment");
			FreeShaftRotation clutchOutput = new FreeShaftRotation();
			InstanceRotation clutchInstance = new InstanceRotation();
			InstanceRotation cogShaftInstance = new InstanceRotation();
			RotationFrame[] frames = {
				new RotationFrame(4200, 1866.6430404663056, .148828125),
				new RotationFrame(4201, 1866.7918685913056, .05),
				new RotationFrame(4202, 1866.8418685913056, 0),
				new RotationFrame(4203, 1866.8418685913056, -.15),
				new RotationFrame(4204, 1866.6918685913056, -1.2)
			};
			for (RotationFrame cog : frames) {
				RotationFrame output = clutchOutput.resolve(cog, .5f, cog.tick(), cog.tick(), false);
				setup(clutchInstance, output, toothOffset);
				float clutchOffset = clutchInstance.setModelOffset(shaftOffset);
				setup(cogShaftInstance, cog, toothOffset);
				float cogShaftOffset = cogShaftInstance.setModelOffset(expectedShaftOffset);
				for (float partial : new float[]{0, .25f, .5f, .75f, 1}) {
					float tick = cog.tick() + partial;
					double shaftAngle = cog.angleAt(tick) + expectedShaftOffset;
					float animated = tick / 20f * output.shaderSpeed();
					angle(shaftAngle, output.angleAt(tick) + shaftOffset,
						"ordinary clutch half and large-cog shaft meet at the same angle");
					angle(shaftAngle, clutchOffset + animated,
						"Flywheel clutch half keeps shaft alignment through updates and reversal");
					angle(cogShaftOffset + animated, clutchOffset + animated,
						"clutch and cog instances have no tooth-offset seam");
					angle(cog.angleAt(tick) + toothOffset, shader(cog, tick, toothOffset),
						"correcting the clutch shaft preserves the cog's tooth alignment");
				}
			}
		}
	}

	private static float setup(InstanceRotation instance, RotationFrame frame, float defaultOffset) {
		instance.beginSetup();
		instance.setModelOffset(defaultOffset);
		return instance.finishSetup(frame);
	}

	private static float shader(RotationFrame frame, float renderTicks, float offset) {
		// The actual Create shader evaluates offset + flw_renderSeconds * speed.
		return frame.shaderOffset(offset) + (renderTicks / 20f) * frame.shaderSpeed();
	}

	private static void angle(double expected, double actual, String message) {
		near(0, Math.IEEEremainder(actual - expected, 360), 0.002, message);
	}

	private static void near(double expected, double actual, double tolerance, String message) {
		check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
			message + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}

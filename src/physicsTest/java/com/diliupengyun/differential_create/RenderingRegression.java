package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.client.PortRotation;
import com.diliupengyun.differential_create.client.RotationFrame;
import com.diliupengyun.differential_create.client.InstanceRotation;
import com.diliupengyun.differential_create.client.FreeShaftRotation;

/** Exercises the production animation math without a GPU or a Minecraft client. */
public final class RenderingRegression {
	private static int checks;

	public static void run() {
		largeCogOffsets();
		freeClutchOutput();
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

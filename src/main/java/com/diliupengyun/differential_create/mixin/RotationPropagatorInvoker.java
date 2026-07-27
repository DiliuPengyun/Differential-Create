package com.diliupengyun.differential_create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

/**
 * Access to the propagation's own conveyed-speed computation so merge physics
 * uses the exact same connection modifier (axis, cog, belt and custom
 * connections included).
 */
@Mixin(RotationPropagator.class)
public interface RotationPropagatorInvoker {

	@Invoker("getConveyedSpeed")
	static float differentialcreate$invokeGetConveyedSpeed(KineticBlockEntity from, KineticBlockEntity to) {
		throw new AssertionError("Mixin invoker was not applied");
	}

}

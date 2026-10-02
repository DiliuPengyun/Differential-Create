package com.diliupengyun.differential_create.kinetics;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

/** Material tier is independent of whether a device has inertia or consumes torque. */
public final class StrengthRegistry {
	public static final TagKey<Block> BRASS = TagKey.create(Registries.BLOCK,
		ResourceLocation.fromNamespaceAndPath("differentialcreate", "brass_strength"));
	private StrengthRegistry() {}
	public static double of(KineticBlockEntity be) {
		if (be instanceof CreativeMotorBlockEntity) return Double.POSITIVE_INFINITY;
		return be.getBlockState().is(BRASS) ? PhysicsTuning.BRASS_STRENGTH : PhysicsTuning.ANDESITE_STRENGTH;
	}
}

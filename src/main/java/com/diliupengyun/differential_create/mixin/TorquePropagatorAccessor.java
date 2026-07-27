package com.diliupengyun.differential_create.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;

import net.minecraft.world.level.LevelAccessor;

/**
 * Access to Create's kinetic network registry so the ticker can iterate all
 * networks every server tick.
 */
@Mixin(TorquePropagator.class)
public interface TorquePropagatorAccessor {

	@Accessor("networks")
	static Map<LevelAccessor, Map<Long, KineticNetwork>> differentialcreate$getNetworks() {
		throw new AssertionError("Mixin accessor was not applied");
	}

}

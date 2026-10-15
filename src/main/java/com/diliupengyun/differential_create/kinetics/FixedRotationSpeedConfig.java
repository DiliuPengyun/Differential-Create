package com.diliupengyun.differential_create.kinetics;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Supply Create's current API field without registering a rotation limit in the config GUI. */
public final class FixedRotationSpeedConfig extends ConfigBase.ConfigInt {

	public FixedRotationSpeedConfig(ConfigBase owner) {
		owner.super("maxRotationSpeed", 256, 64, Integer.MAX_VALUE);
	}

	@Override public void register(ModConfigSpec.Builder builder) {}
	@Override public Integer get() { return 256; }
	@Override public void set(Integer ignored) {}
}

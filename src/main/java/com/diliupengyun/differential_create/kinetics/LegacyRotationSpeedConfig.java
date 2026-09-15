package com.diliupengyun.differential_create.kinetics;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Binary-compatible constant for Create's old field; never added to the config specification. */
public final class LegacyRotationSpeedConfig extends ConfigBase.ConfigInt {

	public LegacyRotationSpeedConfig(ConfigBase owner) {
		owner.super("maxRotationSpeed", 256, 64, Integer.MAX_VALUE);
	}

	@Override public void register(ModConfigSpec.Builder builder) {}
	@Override public Integer get() { return 256; }
	@Override public void set(Integer ignored) {}
}

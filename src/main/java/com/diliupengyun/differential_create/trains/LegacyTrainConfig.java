package com.diliupengyun.differential_create.trains;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Keep public Create fields binary-compatible without obsolete config entries. */
public final class LegacyTrainConfig extends ConfigBase.ConfigFloat {
    private final double constant;
    public LegacyTrainConfig(ConfigBase owner, String name, float constant) { owner.super(name, constant, 0, Float.MAX_VALUE); this.constant = constant; }
    @Override public void register(ModConfigSpec.Builder builder) {}
    @Override public Double get() { return constant; }
    @Override public void set(Double ignored) {}
}

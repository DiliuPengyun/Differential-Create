package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

public final class RatioValueBehaviour extends KineticScrollValueBehaviour {
	public RatioValueBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
		super(Component.translatable("gui.differentialcreate.ratio_setting"), be, slot);
		between(-256, 256);
		withFormatter(v -> String.format(java.util.Locale.ROOT, "%.4g×", v / 16.0));
	}
	@Override public MutableComponent formatSettings(ValueSettings settings) {
		return Component.literal(String.format(java.util.Locale.ROOT, "%.4g× %s", settings.value() / 16.0,
			settings.row() == 0 ? "⟳" : "⟲"));
	}
	@Override public void setValueSettings(Player player, ValueSettings settings, boolean ctrlHeld) {
		int value = Math.max(0, settings.value());
		setValue(settings.row() == 0 ? -value : value);
	}
	@Override public String getClipboardKey() { return "GearRatioSixteenths"; }
}

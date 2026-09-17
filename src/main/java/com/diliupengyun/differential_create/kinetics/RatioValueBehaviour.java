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
		withFormatter(RatioValueBehaviour::formatRatio);
	}
	@Override public MutableComponent formatSettings(ValueSettings settings) {
		int value = settings.row() == 0 ? -settings.value() : settings.value();
		return Component.literal(formatRatio(value) + " " + (settings.row() == 0 ? "⟳" : "⟲"));
	}
	private static String formatRatio(int value) {
		return String.format(java.util.Locale.ROOT, value == 0 ? "%.4g×" : "%+.4g×", value / 16.0);
	}
	@Override public void setValueSettings(Player player, ValueSettings settings, boolean ctrlHeld) {
		int value = Math.max(0, settings.value());
		setValue(settings.row() == 0 ? -value : value);
	}
	@Override public String getClipboardKey() { return "GearRatioSixteenths"; }
}

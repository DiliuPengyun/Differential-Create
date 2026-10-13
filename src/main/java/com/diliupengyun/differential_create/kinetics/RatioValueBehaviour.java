package com.diliupengyun.differential_create.kinetics;

import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.BlockHitResult;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;

public final class RatioValueBehaviour extends KineticScrollValueBehaviour {
	public RatioValueBehaviour(SmartBlockEntity be, ValueBoxTransform slot) {
		super(Component.translatable("gui.differentialcreate.ratio_setting"), be, slot);
		between(-256, 256);
		withFormatter(RatioValueBehaviour::formatRatio);
	}
	@Override public ValueSettingsBoard createBoard(Player player, BlockHitResult hit) {
		return new ValueSettingsBoard(Component.translatable("gui.differentialcreate.ratio_scale"), RatioScale.MAX, RatioScale.OCTAVE,
			java.util.List.of(Component.literal("⟳"), Component.literal("⟲"), Component.literal("N")), new ValueSettingsFormatter(this::formatSettings));
	}
	@Override public ValueSettings getValueSettings() {
		return new ValueSettings(value == 0 ? 2 : value < 0 ? 0 : 1, value == 0 ? RatioScale.CENTER : RatioScale.column(value));
	}
	@Override public MutableComponent formatSettings(ValueSettings settings) {
		if (settings.row() == 2) return Component.literal("N");
		int raw = settings.equals(getValueSettings()) ? value : RatioScale.raw(settings.value()) * (settings.row() == 0 ? -1 : 1);
		return Component.literal(formatRatio(raw) + " " + (settings.row() == 0 ? "⟳" : "⟲"));
	}
	private static String formatRatio(int value) {
		return String.format(java.util.Locale.ROOT, value == 0 ? "%.4g×" : "%+.4g×", value / 16.0);
	}
	@Override public void setValueSettings(Player player, ValueSettings settings, boolean ctrlHeld) {
		if (settings.row() < 0 || settings.row() > 2 || settings.value() < 0 || settings.value() > RatioScale.MAX) return;
		// An unchanged initial cursor preserves old and custom ratios between dial coordinates.
		if (settings.equals(getValueSettings())) return;
		int raw = settings.row() == 2 ? 0 : RatioScale.raw(settings.value()) * (settings.row() == 0 ? -1 : 1);
		setValue(raw);
	}
	@Override public String getClipboardKey() { return "GearRatioSixteenths"; }
	@Override public boolean writeToClipboard(HolderLookup.Provider registries, CompoundTag tag, Direction side) {
		tag.putInt("Value", Math.abs(value)); tag.putInt("Row", value < 0 ? 0 : 1); return true;
	}
	@Override public boolean readFromClipboard(HolderLookup.Provider registries, CompoundTag tag, Player player, Direction side, boolean simulate) {
		if (!tag.contains("Value") || !tag.contains("Row")) return false;
		if (!simulate) setValue(Math.clamp(tag.getInt("Value"), 0, 256) * (tag.getInt("Row") == 0 ? -1 : 1));
		return true;
	}
}

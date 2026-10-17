package com.diliupengyun.differential_create.kinetics;

import java.util.Optional;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;

/** Carry prepaid fractional work through Create's existing backtank item patch. */
public final class BacktankChargeData {
    public static final String KEY = "DCBacktankChargeFraction";
    private BacktankChargeData() {}

    public static double fraction(CustomData data) {
        double value = data.copyTag().getDouble(KEY);
        return Double.isFinite(value) && value >= 0 && value < 1 ? value : 0;
    }
    public static double fraction(DataComponentPatch patch) {
        var data = patch.get(DataComponents.CUSTOM_DATA);
        return data == null || data.isEmpty() ? 0 : fraction(data.get());
    }

    public static DataComponentPatch withFraction(DataComponentPatch patch, double fraction) {
        var previous = patch.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = previous == null || previous.isEmpty() ? new CompoundTag() : previous.get().copyTag();
        boolean hasFraction = Double.isFinite(fraction) && fraction > 0 && fraction < 1;
        if (!hasFraction && !tag.contains(KEY)) return patch;
        if (hasFraction) tag.putDouble(KEY, fraction); else tag.remove(KEY);
        var builder = DataComponentPatch.builder();
        for (var entry : patch.entrySet())
            if (entry.getKey() != DataComponents.CUSTOM_DATA) copy(builder, entry.getKey(), entry.getValue());
        if (tag.isEmpty()) builder.remove(DataComponents.CUSTOM_DATA);
        else builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void copy(DataComponentPatch.Builder builder, DataComponentType<T> type, Optional<?> value) {
        if (value.isPresent()) builder.set(type, (T) value.get()); else builder.remove(type);
    }
}

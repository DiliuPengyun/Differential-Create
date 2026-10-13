package com.diliupengyun.differential_create.trains;

import com.simibubi.create.api.registry.CreateDataMaps;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The native burner acceptance/expiry rules, with storage as its authoritative
 * state instead of a server world block entity that disappears on assembly. */
public final class MountedBurner {
    // Create 6.0.x still accepts these old datapack tags after consulting data
    // maps. Keep that compatibility without depending on its deprecated enum.
    private static final TagKey<Item> LEGACY_SPECIAL = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("create", "blaze_burner_fuel/special"));
    private static final TagKey<Item> LEGACY_REGULAR = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("create", "blaze_burner_fuel/regular"));
    public int fuel, remaining;
    public boolean creative;
    private HeatLevel creativeHeat;
    public MountedBurner(CompoundTag tag, HeatLevel heat) {
        fuel = Math.clamp(tag.getInt("fuelLevel"), 0, 2);
        remaining = Math.max(0, tag.getInt("burnTimeRemaining"));
        creative = tag.getBoolean("isCreative"); creativeHeat = heat;
    }
    public void tick() {
        if (creative) return;
        if (remaining > 0) remaining--;
        if (remaining > 0) return;
        if (fuel == 2) { fuel = 1; remaining = 5000; } else fuel = 0;
    }
    public HeatLevel heat() {
        if (creative) return creativeHeat;
        return switch (fuel) { case 2 -> HeatLevel.SEETHING; case 1 -> remaining < 125 ? HeatLevel.FADING : HeatLevel.KINDLED; default -> HeatLevel.SMOULDERING; };
    }
    public float heatScore() { return heat() == HeatLevel.SEETHING ? 2 : heat() == HeatLevel.KINDLED ? 1 : 0; }
    public boolean accepts(ItemStack stack, boolean execute) {
        if (creative || stack.isEmpty()) return false;
        var holder = stack.getItemHolder();
        var special = holder.getData(CreateDataMaps.SUPERHEATED_BLAZE_BURNER_FUELS);
        var normal = holder.getData(CreateDataMaps.REGULAR_BLAZE_BURNER_FUELS);
        int nextFuel, time;
        if (special != null) { nextFuel = 2; time = special.burnTime(); }
        else if (normal != null) { nextFuel = 1; time = normal.burnTime(); }
        else if (stack.is(LEGACY_SPECIAL)) { nextFuel = 2; time = 3200; }
        else {
            time = stack.getBurnTime(null);
            if (time <= 0 && stack.is(LEGACY_REGULAR)) time = 1600;
            nextFuel = time > 0 ? 1 : 0;
        }
        if (nextFuel == 0 || nextFuel < fuel) return false;
        if (nextFuel == fuel) {
            if (remaining > 500) return false;
            time += remaining;
        }
        if (execute) { fuel = nextFuel; remaining = time; }
        return true;
    }
    public void write(CompoundTag tag) {
        tag.putInt("fuelLevel", fuel); tag.putInt("burnTimeRemaining", remaining); tag.putBoolean("isCreative", creative);
    }
}

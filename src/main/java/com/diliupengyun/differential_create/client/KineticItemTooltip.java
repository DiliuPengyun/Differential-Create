package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.List;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.StrengthRegistry;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.lang.FontHelper.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

/** Inventory values describe the bare component, never a fictitious running network. */
public final class KineticItemTooltip {
    private KineticItemTooltip() {}

    public static void register() {
        for (var item : List.of(ModContent.BRASS_CLUTCH.asItem(), ModContent.COMPOSITE_FRICTION_PLATE.asItem(),
            ModContent.STEAM_HOSE.asItem(), ModContent.CONDENSER_PIPE.asItem(), ModContent.GEAR_LEVER.asItem())) {
            TooltipModifier.REGISTRY.register(item, new ItemDescription.Modifier(item, Palette.STANDARD_CREATE)
                .andThen(TooltipModifier.mapNull(KineticStats.create(item))));
        }
    }

    public static List<Component> describe(Block block, Player player, boolean expanded) {
        List<Component> lines = new ArrayList<>();
        boolean goggles = player != null && GogglesItem.isWearingGoggles(player);
        if (block instanceof SteamEngineBlock) {
            rating(lines, "torque_output", BlockStressValues.getCapacity(block) * PhysicsTuning.STEAM_TORQUE_PER_CAPACITY, true, goggles);
            return lines;
        }
        if (!(block instanceof IRotate)) return lines;
        boolean clutch = block == AllBlocks.CLUTCH.get() || block == ModContent.BRASS_CLUTCH.get();

        if (expanded) {
            title(lines, "mechanical_properties");
            property(lines, "inertia", InertiaRegistry.getBaseInertia(block));
            double strength = StrengthRegistry.of(block);
            if (Double.isInfinite(strength)) lines.add(Component.translatable("tooltip.differentialcreate.unlimited_strength")
                .withStyle(ChatFormatting.DARK_GRAY));
            else property(lines, "strength", strength);
            if (clutch) property(lines, "torque_limit_value", block == AllBlocks.CLUTCH.get()
                ? PhysicsTuning.CLUTCH_TORQUE : PhysicsTuning.BRASS_CLUTCH_TORQUE);
        }
        // Create's clutch has no impact/capacity statistic. The mod's finite
        // friction rating belongs to the expanded component properties.
        if (clutch) return lines;

        Block ratingBlock = block instanceof com.simibubi.create.content.kinetics.crank.ValveHandleBlock
            ? AllBlocks.COPPER_VALVE_HANDLE.get() : block;
        double capacity = BlockStressValues.getCapacity(ratingBlock), impact = BlockStressValues.getImpact(ratingBlock);
        List<Component> torque = new ArrayList<>();
        if (block == AllBlocks.MECHANICAL_PUMP.get() || block == AllBlocks.CRUSHING_WHEEL.get()) {
            variable(torque, "torque_impact", "variable_load");
        } else if (block == AllBlocks.ENCASED_FAN.get()) {
            rating(torque, "fan_impact", impact, false, goggles);
        } else if (block == AllBlocks.MECHANICAL_BEARING.get() || block == AllBlocks.CLOCKWORK_BEARING.get()
            || block == AllBlocks.MECHANICAL_PISTON.get() || block == AllBlocks.STICKY_MECHANICAL_PISTON.get()
            || block == AllBlocks.ROPE_PULLEY.get() || block == AllBlocks.ELEVATOR_PULLEY.get()
            || block == AllBlocks.GANTRY_CARRIAGE.get() || block == AllBlocks.GANTRY_SHAFT.get()) {
            variable(torque, "torque_impact", "variable_load");
        } else if (block == AllBlocks.WINDMILL_BEARING.get()) {
            variable(torque, "torque_output", "variable_sails");
        } else if (capacity > 0) {
            // SourcePhysics.curve: no-load speed = 2 * generated, K = capacity / |generated|.
            rating(torque, "starting_torque", 2 * capacity, true, goggles);
        } else if (!((IRotate) block).hideStressImpact() && impact > 0) {
            rating(torque, "torque_impact", impact, false, goggles);
        }
        if (!lines.isEmpty() && !torque.isEmpty()) lines.add(CommonComponents.EMPTY);
        lines.addAll(torque);
        return lines;
    }

    private static void property(List<Component> lines, String key, double value) {
        lines.add(Component.translatable("tooltip.differentialcreate." + key, CreateLang.number(value).style(ChatFormatting.GRAY).component())
            .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void title(List<Component> lines, String key) {
        lines.add(Component.translatable("tooltip.differentialcreate." + key).withStyle(ChatFormatting.GRAY));
    }

    private static void variable(List<Component> lines, String key, String value) {
        title(lines, key);
        lines.add(Component.translatable("tooltip.differentialcreate." + value).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void rating(List<Component> lines, String key, double value, boolean output, boolean goggles) {
        title(lines, key);
        var config = AllConfigs.server().kinetics;
        double medium = output ? config.mediumCapacity.get() : config.mediumStressImpact.get();
        double high = output ? config.highCapacity.get() : config.highStressImpact.get();
        int tier = value >= high ? 2 : value >= medium ? 1 : 0;
        StressImpact color = StressImpact.values()[output ? 2 - tier : tier];
        var row = CreateLang.text(TooltipHelper.makeProgressBar(3, tier + 1)).style(color.getAbsoluteColor());
        if (goggles) row.add(CreateLang.number(value)).text(" su/RPM");
        else row.translate((output ? "tooltip.capacityProvided." : "tooltip.stressImpact.") + List.of("low", "medium", "high").get(tier));
        row.addTo(lines);
    }
}

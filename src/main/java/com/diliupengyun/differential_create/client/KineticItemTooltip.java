package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.InertiaRegistry;
import com.diliupengyun.differential_create.kinetics.PhysicsTuning;
import com.diliupengyun.differential_create.kinetics.SequencerMotion;
import com.diliupengyun.differential_create.kinetics.StrengthRegistry;
import com.google.common.collect.ImmutableList;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Inventory values describe the bare component, never a fictitious running network. */
public final class KineticItemTooltip {
    private static final Set<String> PROPERTY_KEYS = Set.of("tooltip.differentialcreate.inertia",
        "tooltip.differentialcreate.input_inertia", "tooltip.differentialcreate.output_inertia",
        "tooltip.differentialcreate.strength", "tooltip.differentialcreate.unlimited_strength",
        "tooltip.differentialcreate.torque_limit_value");

    private KineticItemTooltip() {}

    public static void register() {
        for (var item : List.of(ModContent.BRASS_CLUTCH.asItem(), ModContent.GEAR_LEVER.asItem())) {
            TooltipModifier.REGISTRY.register(item, new ItemDescription.Modifier(item, Palette.STANDARD_CREATE)
                .andThen(TooltipModifier.mapNull(KineticStats.create(item))));
        }
    }

    /** Preserve native descriptions; properties supply a Shift entry when none exists. */
    public static ItemDescription description(Item item, ItemDescription original) {
        if (!(item instanceof BlockItem blockItem)) return original;
        var properties = properties(blockItem.getBlock());
        if (properties.isEmpty()) return original;

        var expanded = new ArrayList<Component>();
        if (original == null) {
            var collapsed = ImmutableList.of(propertyHint(false));
            expanded.add(propertyHint(true));
            expanded.add(CommonComponents.EMPTY);
            expanded.addAll(properties);
            return new ItemDescription(collapsed, ImmutableList.copyOf(expanded), collapsed);
        }
        expanded.addAll(original.linesOnShift());
        expanded.add(CommonComponents.EMPTY);
        expanded.addAll(properties);
        return new ItemDescription(original.lines(), ImmutableList.copyOf(expanded), original.linesOnCtrl());
    }

    private static Component propertyHint(boolean expanded) {
        return Component.translatable("tooltip.differentialcreate.hold_for_properties",
            CreateLang.translateDirect("tooltip.keyShift").withStyle(expanded ? ChatFormatting.WHITE : ChatFormatting.GRAY))
            .withStyle(ChatFormatting.DARK_GRAY);
    }

    /** Ponder may run before or after Create's tooltip event, including while W fills its progress bar. */
    public static int ponderIndex(List<Component> tooltip, int fallback) {
        for (int i = tooltip.size() - 1; i >= 0; i--) {
            if (tooltip.get(i).getContents() instanceof TranslatableContents text
                && PROPERTY_KEYS.contains(text.getKey())) return i + 1;
        }
        return fallback;
    }

    /** ItemDescription caches prose by language, but tags and rotor values may change on reload. */
    public static void refreshProperties(List<Component> tooltip, Block block) {
        for (int start = 0; start < tooltip.size(); start++) {
            if (!(tooltip.get(start).getContents() instanceof TranslatableContents title)
                || !title.getKey().equals("tooltip.differentialcreate.mechanical_properties")) continue;
            int end = start + 1;
            while (end < tooltip.size() && tooltip.get(end).getContents() instanceof TranslatableContents property
                && PROPERTY_KEYS.contains(property.getKey())) end++;
            tooltip.subList(start, end).clear();
            tooltip.addAll(start, properties(block));
            return;
        }
    }

    public static List<Component> properties(Block block) {
        List<Component> lines = new ArrayList<>();
        if (block instanceof SteamEngineBlock) block = AllBlocks.POWERED_SHAFT.get();
        if (!(block instanceof IRotate)) return lines;
        title(lines, "mechanical_properties");
        if (block == AllBlocks.SEQUENCED_GEARSHIFT.get()) {
            // The two rotors can turn independently; one inertia would omit the output rotor.
            property(lines, "input_inertia", InertiaRegistry.getBaseInertia(block));
            property(lines, "output_inertia", SequencerMotion.OUTPUT_INERTIA);
        } else property(lines, "inertia", InertiaRegistry.getBaseInertia(block));
        double strength = StrengthRegistry.of(block);
        if (Double.isInfinite(strength)) lines.add(Component.translatable("tooltip.differentialcreate.unlimited_strength")
            .withStyle(ChatFormatting.DARK_GRAY));
        else property(lines, "strength", strength);
        if (block == AllBlocks.CLUTCH.get() || block == ModContent.BRASS_CLUTCH.get())
            property(lines, "torque_limit_value", block == AllBlocks.CLUTCH.get()
                ? PhysicsTuning.CLUTCH_TORQUE : PhysicsTuning.BRASS_CLUTCH_TORQUE);
        return lines;
    }

    public static List<Component> stats(Block block, Player player) {
        List<Component> torque = new ArrayList<>();
        boolean goggles = player != null && GogglesItem.isWearingGoggles(player);
        if (block instanceof SteamEngineBlock) {
            rating(torque, "torque_output", BlockStressValues.getCapacity(block) * PhysicsTuning.STEAM_TORQUE_PER_CAPACITY, true, goggles);
            return torque;
        }
        if (!(block instanceof IRotate)) return torque;
        // Create's clutch has no impact/capacity statistic. The mod's finite
        // friction rating belongs to the expanded component properties.
        if (block == AllBlocks.CLUTCH.get() || block == ModContent.BRASS_CLUTCH.get()) return torque;

        Block ratingBlock = block instanceof com.simibubi.create.content.kinetics.crank.ValveHandleBlock
            ? AllBlocks.COPPER_VALVE_HANDLE.get() : block;
        double capacity = BlockStressValues.getCapacity(ratingBlock), impact = BlockStressValues.getImpact(ratingBlock);
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
        return torque;
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

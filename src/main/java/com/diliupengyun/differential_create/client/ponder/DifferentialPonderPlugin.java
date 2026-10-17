package com.diliupengyun.differential_create.client.ponder;

import java.util.ArrayList;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/** Create registers first. Replace incompatible demonstrations on every index reload. */
public final class DifferentialPonderPlugin implements PonderPlugin {
    public static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "physics");

    @Override public String getModId() { return DifferentialCreate.MODID; }

    @Override public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        var registered = PonderIndex.getSceneAccess().getRegisteredEntries();
        for (var entry : new ArrayList<>(registered)) {
            StoryBoardEntry old = entry.getValue();
            if (!old.getNamespace().equals("create")) continue;
            Replacement replacement = replacement(old);
            if (replacement == null) continue;
            registered.remove(entry);
            var updated = helper.addStoryBoard(old.getComponent(), replacement.schematic(), replacement.board(),
                old.getTags().toArray(ResourceLocation[]::new));
            updated.getOrderingEntries().addAll(old.getOrderingEntries());
        }

        add(helper, DifferentialPonderScenes::dynamics, AllBlocks.SHAFT, AllBlocks.COGWHEEL, AllBlocks.LARGE_COGWHEEL,
            AllBlocks.FLYWHEEL, AllBlocks.WATER_WHEEL, AllBlocks.LARGE_WATER_WHEEL, AllBlocks.WINDMILL_BEARING,
            AllBlocks.CREATIVE_MOTOR, AllBlocks.GEARBOX, AllBlocks.GEARSHIFT, AllBlocks.ENCASED_CHAIN_DRIVE,
            AllBlocks.ADJUSTABLE_CHAIN_GEARSHIFT, AllItems.BELT_CONNECTOR, AllItems.VERTICAL_GEARBOX,
            AllBlocks.HAND_CRANK, AllBlocks.COPPER_VALVE_HANDLE);
        add(helper, DifferentialPonderScenes::clutch, ModContent.BRASS_CLUTCH);
        add(helper, DifferentialPonderScenes::continuousWork, AllBlocks.MECHANICAL_PRESS, AllBlocks.MILLSTONE,
            AllBlocks.MECHANICAL_MIXER, AllBlocks.MECHANICAL_SAW, AllBlocks.MECHANICAL_DRILL, AllBlocks.DEPLOYER,
            AllBlocks.MECHANICAL_CRAFTER, AllBlocks.MECHANICAL_ARM, AllBlocks.BASIN);
        add(helper, DifferentialPonderScenes::backtank, AllItems.COPPER_BACKTANK, AllItems.NETHERITE_BACKTANK);
        add(helper, DifferentialPonderScenes::ejector, AllBlocks.WEIGHTED_EJECTOR);
        add(helper, DifferentialPonderScenes::crusher, AllBlocks.CRUSHING_WHEEL);
        add(helper, DifferentialPonderScenes::steam, AllBlocks.STEAM_ENGINE, AllBlocks.FLUID_TANK);
        add(helper, DifferentialPonderScenes::fan, AllBlocks.ENCASED_FAN);
        add(helper, DifferentialPonderScenes::structureLoad, AllBlocks.MECHANICAL_PISTON,
            AllBlocks.STICKY_MECHANICAL_PISTON, AllBlocks.ROPE_PULLEY, AllBlocks.ELEVATOR_PULLEY,
            AllBlocks.CLOCKWORK_BEARING, AllBlocks.GANTRY_SHAFT, AllBlocks.GANTRY_CARRIAGE,
            AllBlocks.MECHANICAL_HARVESTER, AllBlocks.MECHANICAL_PLOUGH, AllBlocks.MECHANICAL_ROLLER);
        add(helper, DifferentialPonderScenes::pumpNetwork, AllBlocks.FLUID_PIPE, AllBlocks.SMART_FLUID_PIPE,
            AllBlocks.FLUID_VALVE, AllBlocks.FLUID_TANK, AllBlocks.HOSE_PULLEY);
        add(helper, DifferentialPonderScenes::minecart, AllBlocks.CART_ASSEMBLER, AllBlocks.CONTROLLER_RAIL);
        for (var item : new ItemLike[]{AllBlocks.STEAM_ENGINE, AllBlocks.TRAIN_CONTROLS, AllBlocks.TRACK_STATION,
            AllBlocks.RAILWAY_CASING, ModContent.STEAM_HOSE, ModContent.CONDENSER_PIPE, ModContent.GEAR_LEVER})
            helper.addStoryBoard(id(item), create("train_controls"), DifferentialPonderScenes::trainPower);
        helper.addStoryBoard(ModContent.GEAR_LEVER.getId(), create("train_controls"), DifferentialPonderScenes::trainDriving);
    }

    private static Replacement replacement(StoryBoardEntry entry) {
        if (!entry.getSchematicLocation().getNamespace().equals("create")) return null;
        return switch (entry.getSchematicLocation().getPath()) {
            case "clutch" -> of(DifferentialPonderScenes::clutch);
            case "speed_controller" -> of(DifferentialPonderScenes::ratio);
            case "sequenced_gearshift" -> of(DifferentialPonderScenes::sequencer);
            case "gauges" -> of(entry.getComponent().getPath().equals("stressometer")
                ? DifferentialPonderScenes::loadGauge : DifferentialPonderScenes::speedGauge);
            case "mechanical_pump/flow" -> of(DifferentialPonderScenes::pumpDirection);
            case "mechanical_pump/speed" -> of(DifferentialPonderScenes::pumpNetwork);
            case "mechanical_bearing/anchor" -> of(DifferentialPonderScenes::bearing);
            case "mechanical_bearing/modes" -> of(DifferentialPonderScenes::structureLoad);
            case "portable_interface/transfer" -> of((s, u) -> DifferentialPonderScenes.docking(s, u, false));
            case "portable_interface/transfer_fluid" -> of((s, u) -> DifferentialPonderScenes.docking(s, u, true));
            case "train_controls" -> new Replacement(create("train_controls"), DifferentialPonderScenes::trainDriving);
            default -> null;
        };
    }

    private static Replacement of(PonderStoryBoard board) { return new Replacement(EMPTY, board); }
    private record Replacement(ResourceLocation schematic, PonderStoryBoard board) {}
    private static void add(PonderSceneRegistrationHelper<ResourceLocation> helper, PonderStoryBoard board, ItemLike... items) {
        for (var item : items) helper.addStoryBoard(id(item), EMPTY, board);
    }
    private static ResourceLocation id(ItemLike item) { return BuiltInRegistries.ITEM.getKey(item.asItem()); }
    private static ResourceLocation create(String path) { return ResourceLocation.fromNamespaceAndPath("create", path); }
}

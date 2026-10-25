package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/** Add physics supplements without replacing Create's templates or storyboards. */
public final class DifferentialPonderPlugin implements PonderPlugin {
    public static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "physics");

    @Override public String getModId() { return DifferentialCreate.MODID; }

    @Override public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        add(helper, DifferentialPonderScenes::dynamics, AllBlocks.SHAFT, AllBlocks.COGWHEEL, AllBlocks.LARGE_COGWHEEL,
            AllBlocks.FLYWHEEL, AllBlocks.WATER_WHEEL, AllBlocks.LARGE_WATER_WHEEL, AllBlocks.WINDMILL_BEARING,
            AllBlocks.CREATIVE_MOTOR, AllBlocks.GEARBOX, AllBlocks.GEARSHIFT, AllBlocks.ENCASED_CHAIN_DRIVE,
            AllBlocks.ADJUSTABLE_CHAIN_GEARSHIFT, AllItems.BELT_CONNECTOR, AllItems.VERTICAL_GEARBOX,
            AllBlocks.HAND_CRANK, AllBlocks.COPPER_VALVE_HANDLE);
        add(helper, DifferentialPonderScenes::clutch, AllBlocks.CLUTCH, ModContent.BRASS_CLUTCH);
        add(helper, DifferentialPonderScenes::ratio, AllBlocks.ROTATION_SPEED_CONTROLLER);
        add(helper, DifferentialPonderScenes::sequencer, AllBlocks.SEQUENCED_GEARSHIFT);
        add(helper, DifferentialPonderScenes::loadGauge, AllBlocks.STRESSOMETER);
        add(helper, DifferentialPonderScenes::speedGauge, AllBlocks.SPEEDOMETER);
        add(helper, DifferentialPonderScenes::pumpDirection, AllBlocks.MECHANICAL_PUMP);
        add(helper, DifferentialPonderScenes::bearing, AllBlocks.MECHANICAL_BEARING);
        add(helper, (s, u) -> DifferentialPonderScenes.docking(s, u, false), AllBlocks.PORTABLE_STORAGE_INTERFACE);
        add(helper, (s, u) -> DifferentialPonderScenes.docking(s, u, true), AllBlocks.PORTABLE_FLUID_INTERFACE);
        add(helper, DifferentialPonderScenes::continuousWork, AllBlocks.MECHANICAL_PRESS, AllBlocks.MILLSTONE,
            AllBlocks.MECHANICAL_MIXER, AllBlocks.MECHANICAL_SAW, AllBlocks.MECHANICAL_DRILL, AllBlocks.DEPLOYER,
            AllBlocks.MECHANICAL_CRAFTER, AllBlocks.MECHANICAL_ARM, AllBlocks.BASIN);
        add(helper, DifferentialPonderScenes::ejector, AllBlocks.WEIGHTED_EJECTOR);
        add(helper, DifferentialPonderScenes::crusher, AllBlocks.CRUSHING_WHEEL);
        add(helper, DifferentialPonderScenes::steam, AllBlocks.STEAM_ENGINE);
        add(helper, DifferentialPonderScenes::fan, AllBlocks.ENCASED_FAN);
        add(helper, DifferentialPonderScenes::structureLoad, AllBlocks.MECHANICAL_BEARING,
            AllBlocks.ROPE_PULLEY, AllBlocks.ELEVATOR_PULLEY,
            AllBlocks.CLOCKWORK_BEARING, AllBlocks.GANTRY_SHAFT, AllBlocks.GANTRY_CARRIAGE,
            AllBlocks.MECHANICAL_HARVESTER, AllBlocks.MECHANICAL_PLOUGH, AllBlocks.MECHANICAL_ROLLER);
        add(helper, DifferentialPonderScenes::pumpNetwork, AllBlocks.FLUID_PIPE);
        add(helper, DifferentialPonderScenes::minecart, AllBlocks.CART_ASSEMBLER, AllBlocks.CONTROLLER_RAIL);
        for (var item : new ItemLike[]{AllBlocks.STEAM_ENGINE, AllBlocks.TRAIN_CONTROLS, AllBlocks.TRACK_STATION,
            AllBlocks.RAILWAY_CASING, ModContent.STEAM_HOSE, ModContent.CONDENSER_PIPE, ModContent.GEAR_LEVER})
            helper.addStoryBoard(id(item), create("train_controls"), DifferentialPonderScenes::trainPower);
        helper.addStoryBoard(ModContent.GEAR_LEVER.getId(), create("train_controls"), DifferentialPonderScenes::trainDriving);
        helper.addStoryBoard(AllBlocks.TRAIN_CONTROLS.getId(), create("train_controls"), DifferentialPonderScenes::trainDriving);
    }

    @Override public void registerSharedText(SharedTextRegistrationHelper helper) {
        helper.registerSharedText("bearing_manual", "Right-click the Bearing with an empty hand to assemble or disassemble it.");
    }

    private static void add(PonderSceneRegistrationHelper<ResourceLocation> helper, PonderStoryBoard board, ItemLike... items) {
        for (var item : items) helper.addStoryBoard(id(item), EMPTY, board);
    }
    private static ResourceLocation id(ItemLike item) { return BuiltInRegistries.ITEM.getKey(item.asItem()); }
    private static ResourceLocation create(String path) { return ResourceLocation.fromNamespaceAndPath("create", path); }
}

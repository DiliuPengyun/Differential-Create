package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.client.RenderObservationCache;
import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.diliupengyun.differential_create.trains.GearLeverBlock;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Opt-in resource/BER and integrated-server hose checks in an owned test world. */
public final class TrainClientSmokeTests {
    private static final BlockPos ENGINE = new BlockPos(0, -59, 0), TANK = ENGINE.south(10);
    private static int frames;
    private static int renderedFrames;
    private static boolean done, worldRequested, fixtureRequested, changesRequested;
    private static long deadline;
    private static CompletableFuture<?> fixture, changes;
    private TrainClientSmokeTests() {}
    public static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (done) return;
        try {
            if (!worldRequested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                resources(mc);
                worldRequested = true;
                deadline = System.nanoTime() + 120_000_000_000L;
                // These settings and this save live only in build/train-client/runs/.
                mc.options.renderDistance().set(2);
                mc.options.simulationDistance().set(5);
                var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                var settings = new LevelSettings("Train hose render smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("train_hose_render", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for integrated-server hose rendering");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (!fixtureRequested) {
                fixtureRequested = true;
                mc.setScreen(null);
                fixture = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    for (int i = 0; i < 64; i++) addEngine(level, i);
                    level.setBlockAndUpdate(TANK, AllBlocks.FLUID_TANK.getDefaultState());
                    SteamHoseLinks.connect((SteamEngineBlockEntity) level.getBlockEntity(ENGINE), (FluidTankBlockEntity) level.getBlockEntity(TANK));
                    level.getServer().getPlayerList().getPlayers().getFirst().teleportTo(3.5, -55, -5.5);
                    return null;
                });
            }
            if (!fixture.isDone()) return;
            fixture.join();
            if (!(mc.level.getBlockEntity(ENGINE) instanceof SteamEngineBlockEntity engine)
                || !TANK.subtract(ENGINE).equals(((SteamHoseLinks.EngineLink) engine).differentialcreate$hose())
                || SteamHoseLinks.tank(engine) == null) return;
            mc.player.setYRot(0); mc.player.setXRot(30);
            if (renderedFrames >= 30 && !changesRequested) {
                if (observations().snapshot(entry -> false).size() < 64) return;
                changesRequested = true;
                changes = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    for (int i = 64; i < 128; i++) addEngine(level, i);
                    for (int i = 1; i < 32; i++) level.removeBlock(enginePos(i), false);
                    return null;
                });
            }
            if (changes == null || !changes.isDone() || renderedFrames < 120) return;
            changes.join();
            var live = observations().snapshot(entry -> false);
            if (live.size() < 97) return;
            for (var observed : live) if (observed.getLevel() != mc.level || observed.isRemoved())
                throw new AssertionError("Hose cache retained a removed or server-world engine");
            DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_SMOKE_PASS: models/BER/HUD loaded; {} world frames, {} client-only engines after concurrent server additions/removals",
                renderedFrames, live.size());
            done = true; mc.stop();
        } catch (Throwable error) {
            done = true;
            DifferentialCreate.LOGGER.error("DC_TRAIN_CLIENT_SMOKE_FAIL", error);
            mc.stop();
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES && fixture != null && fixture.isDone()) renderedFrames++;
    }

    private static BlockPos enginePos(int index) { return ENGINE.offset(index % 16, 0, index / 16); }

    private static void addEngine(ServerLevel level, int index) {
        var pos = enginePos(index);
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, AllBlocks.STEAM_ENGINE.getDefaultState());
        var engine = (SteamEngineBlockEntity) level.getBlockEntity(pos);
        // Run the exact client-mixin entry point on the integrated server thread.
        // The old observer adds this server engine to the shared render cache.
        SteamHoseRenderer.observe(engine);
        if (observations().snapshot(entry -> false).contains(engine))
            throw new AssertionError("Integrated server engine entered the client's hose cache");
    }

    @SuppressWarnings("unchecked")
    private static RenderObservationCache<SteamEngineBlockEntity> observations() {
        try {
            var field = SteamHoseRenderer.class.getDeclaredField("ENGINES"); field.setAccessible(true);
            return (RenderObservationCache<SteamEngineBlockEntity>) field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void resources(Minecraft mc) throws ClassNotFoundException {
        for (String type : new String[]{
            "com.simibubi.create.content.trains.bogey.StandardBogeyVisual$Large",
            "com.simibubi.create.content.kinetics.steamEngine.SteamEngineRenderer",
            "com.simibubi.create.content.kinetics.steamEngine.SteamEngineVisual",
            "com.simibubi.create.content.trains.TrainHUD"}) Class.forName(type);
        var missing = mc.getModelManager().getMissingModel();
        if (com.diliupengyun.differential_create.client.SteamBogeyModels.CYLINDERS.get() == missing
            || com.diliupengyun.differential_create.client.SteamBogeyModels.PISTONS.get() == missing)
            throw new AssertionError("Steam bogey cylinder and piston models failed to bake");
        for (int gear = 0; gear <= 3; gear++) {
            var model = mc.getBlockRenderer().getBlockModel(ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, gear));
            if (model == missing) throw new AssertionError("Missing gear lever model " + gear);
        }
        if (mc.getItemRenderer().getItemModelShaper().getItemModel(new ItemStack(ModContent.STEAM_HOSE.get())) == missing)
            throw new AssertionError("Missing hose model");
        var renderer = new StandardBogeyRenderer.Large();
        for (boolean geared : new boolean[]{false, true}) {
            var data = new CompoundTag(); data.putBoolean("DCGeared", geared);
            data.putBoolean(com.diliupengyun.differential_create.trains.SteamBogey.ENGINE, geared);
            new StandardBogeyRenderer.Small().render(data, 30, .5f, new PoseStack(), mc.renderBuffers().bufferSource(), 0xf000f0, 0, true);
            renderer.render(data, 30, .5f, new PoseStack(), mc.renderBuffers().bufferSource(), 0xf000f0, 0, true);
        }
        mc.renderBuffers().bufferSource().endBatch();
        DifferentialCreate.LOGGER.info("Train client resources loaded; starting integrated-server hose rendering check");
    }
}

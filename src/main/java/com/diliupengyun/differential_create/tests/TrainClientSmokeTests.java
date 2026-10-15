package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.client.RenderObservationCache;
import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.diliupengyun.differential_create.trains.GearLeverBlock;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import java.util.HashMap;
import java.util.Map;
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
    private static boolean done, worldRequested, fixtureRequested, changesRequested, interactionsComplete, portsComplete, motionChecked;
    private static int verifiedEngines;
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
                deadline = System.nanoTime() + 240_000_000_000L;
                // These settings and this save live only in build/train-client/runs/.
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.simulationDistance().set(5);
                var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                var settings = new LevelSettings("Train hose render smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("train_hose_render", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for integrated-server hose rendering: paused="
                + mc.isPaused() + ", screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                + ", fixture=" + (fixture == null ? "none" : fixture.isDone()) + ", frames=" + renderedFrames
                + ", engines=" + observations().snapshot(entry -> false).size());
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (!motionChecked) {
                TrainClientHoseRenderTests.verify(mc);
                TrainClientBoilerRenderTests.verify(mc);
                TrainClientFluidTests.verify(mc);
                motionChecked = true;
            }
            if (portsComplete) {
                if (!TrainClientDrivingTests.tick(mc)) return;
                DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_SMOKE_PASS: models/BER/HUD loaded; {} world frames, {} client-only engines after concurrent server additions/removals",
                    renderedFrames, verifiedEngines);
                done = true; mc.stop(); return;
            }
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
            if (!interactionsComplete) interactionsComplete = TrainClientInteractionTests.tick(mc);
            if (!interactionsComplete || !TrainClientHosePortTests.tick(mc)) return;
            verifiedEngines = live.size(); portsComplete = true;
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
        if (com.diliupengyun.differential_create.client.SteamBogeyModels.ENGINE_COVER.get() == missing)
            throw new AssertionError("Steam bogey engine cover model failed to bake");
        for (int gear = 0; gear <= com.diliupengyun.differential_create.trains.TrainMechanics.REVERSE_GEAR; gear++) {
            var model = mc.getBlockRenderer().getBlockModel(ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, gear));
            if (model == missing) throw new AssertionError("Missing gear lever model " + gear);
        }
        var neutral = ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, 0);
        var neutralModel = mc.getBlockRenderer().getBlockModel(neutral);
        int handleVertices = 0;
        for (var quad : neutralModel.getQuads(neutral, null, net.minecraft.util.RandomSource.create(0),
            net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null)) {
            var vertices = quad.getVertices(); int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                float y = Float.intBitsToFloat(vertices[i * stride + 1]); if (y <= 3f / 16 + 1e-4) continue;
                float x = Float.intBitsToFloat(vertices[i * stride]), z = Float.intBitsToFloat(vertices[i * stride + 2]);
                if (x < 7f / 16 - 1e-4 || x > 9f / 16 + 1e-4 || z < 7f / 16 - 1e-4 || z > 9f / 16 + 1e-4)
                    throw new AssertionError("Neutral gear handle must stand in the centre of its base");
                handleVertices++;
            }
        }
        if (handleVertices < 4) throw new AssertionError("Neutral gear handle is missing from its baked model");
        double previousTip = -1;
        for (int gear : new int[]{com.diliupengyun.differential_create.trains.TrainMechanics.REVERSE_GEAR, 0, 1, 2, 3, 4, 5}) {
            var state = ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, gear);
            var model = mc.getBlockRenderer().getBlockModel(state);
            double tip = 0; int tipVertices = 0;
            for (var quad : model.getQuads(state, null, net.minecraft.util.RandomSource.create(0),
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null)) {
                var vertices = quad.getVertices(); int stride = vertices.length / 4;
                for (int i = 0; i < 4; i++) {
                    if (Float.intBitsToFloat(vertices[i * stride + 1]) <= .5f) continue;
                    tip += Float.intBitsToFloat(vertices[i * stride + 2]); tipVertices++;
                }
            }
            if (tipVertices == 0) throw new AssertionError("Missing handle tip in gear " + gear);
            tip /= tipVertices;
            if (tip <= previousTip + .05 || gear == 0 && Math.abs(tip - .5) > 1e-4)
                throw new AssertionError("Physical lever order must be R, centred N, 1 through 5: gear=" + gear + " tip=" + tip);
            previousTip = tip;
        }
        if (mc.getItemRenderer().getItemModelShaper().getItemModel(new ItemStack(ModContent.STEAM_HOSE.get())) == missing)
            throw new AssertionError("Missing hose model");
        bogeyGeometry();
        DifferentialCreate.LOGGER.info("Train client resources loaded; starting integrated-server hose rendering check");
    }

    /** Covers stay within the upper wood; native brass, casing, wheels and rods keep every vertex and UV. */
    private static void bogeyGeometry() {
        int cases = 0;
        for (var renderer : new StandardBogeyRenderer[]{new StandardBogeyRenderer.Small(), new StandardBogeyRenderer.Large()})
            for (float angle : new float[]{0, 30, 90, 173}) for (boolean moving : new boolean[]{false, true}) {
                var ordinary = bogeyVertices(renderer, new CompoundTag(), angle, moving);
                var data = new CompoundTag(); data.putBoolean("DCGeared", true);
                if (!ordinary.equals(bogeyVertices(renderer, data, angle, moving)))
                    throw new AssertionError("Legacy geared flag changed the native bogey geometry");
                data.putBoolean(com.diliupengyun.differential_create.trains.SteamBogey.ENGINE, true);
                var installed = bogeyVertices(renderer, data, angle, moving);
                for (var entry : ordinary.entrySet()) {
                    int remaining = installed.getOrDefault(entry.getKey(), 0) - entry.getValue();
                    if (remaining < 0) throw new AssertionError("Installed engine removed or changed a native wheel/frame/rod vertex");
                    if (remaining == 0) installed.remove(entry.getKey()); else installed.put(entry.getKey(), remaining);
                }
                if (installed.values().stream().mapToInt(Integer::intValue).sum() != 48)
                    throw new AssertionError("Installed engine must add only the flat lid, upper panels, inlet and bolts");
                for (var point : installed.keySet()) {
                    if (Math.abs(point.x()) > .502 || Math.abs(point.z()) > .502 || point.y() < 1.765 || point.y() > 2.02)
                        throw new AssertionError("Installed engine added a part outside the upper core: " + point);
                    if (Math.abs(point.z()) > .377 || point.y() > com.diliupengyun.differential_create.client.SteamBogeyModels.COVER_Y + 1 && Math.abs(point.x()) > .377)
                        throw new AssertionError("Cover overlaps the native brass edging or adhesive face: " + point);
                    if (point.y() <= com.diliupengyun.differential_create.client.SteamBogeyModels.COVER_Y + 1 && Math.abs(point.x()) < .499)
                        throw new AssertionError("Cover added an interior mesh instead of a flush side panel: " + point);
                }
                cases++;
            }
        DifferentialCreate.LOGGER.info("DC_TRAIN_BOGEY_GEOMETRY_PASS: {} small/large angle and stationary/moving render cases preserve native vertices and UVs", cases);
    }

    private static Map<BogeyVertex, Integer> bogeyVertices(StandardBogeyRenderer renderer, CompoundTag data, float angle, boolean moving) {
        var vertices = new BogeyVertices();
        renderer.render(data, angle, .5f, new PoseStack(), type -> vertices, 0xf000f0, 0, moving);
        vertices.flush(); return vertices.points;
    }

    private record BogeyVertex(float x, float y, float z, float u, float v) {}
    private static final class BogeyVertices implements VertexConsumer {
        private final Map<BogeyVertex, Integer> points = new HashMap<>();
        private float x, y, z, u, v;
        private boolean pending;
        private void flush() {
            if (pending) points.merge(new BogeyVertex(x, y, z, u, v), 1, Integer::sum);
            pending = false;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            flush(); this.x = x; this.y = y; this.z = z; u = v = 0; pending = true; return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}

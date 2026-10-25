package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.IEjectorDynamics;
import com.diliupengyun.differential_create.kinetics.ShaftWork;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.depot.EntityLauncher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Actual client packets and native BER in an owned, frozen integrated-server fixture. */
public final class EjectorClientSmokeTests {
    private static final BlockPos POS = new BlockPos(0, -59, 3);
    private static int frames;
    private static boolean requested, done;
    private static long deadline;
    private static CompletableFuture<?> fixture;
    private EjectorClientSmokeTests() {}

    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true;
                deadline = System.nanoTime() + 180_000_000_000L;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.simulationDistance().set(5);
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                var settings = new LevelSettings("Ejector render smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("ejector_render", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for ejector client fixture");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (fixture == null) {
                mc.setScreen(null);
                fixture = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    level.setBlockAndUpdate(POS, AllBlocks.WEIGHTED_EJECTOR.getDefaultState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
                    var be = (EjectorBlockEntity) level.getBlockEntity(POS);
                    be.setTarget(8, 0);
                    var work = (ShaftWork) be;
                    work.differentialcreate$advanceTravel(work.differentialcreate$remainingTravel());
                    var held = new TransportedItemStack(new ItemStack(Items.IRON_INGOT, 32));
                    held.beltPosition = held.prevBeltPosition = .5f;
                    be.getBehaviour(DepotBehaviour.TYPE).setHeldItem(held);
                    be.activateDeferred();
                    be.setTarget(16, 4);
                    level.setBlockAndUpdate(POS, be.getBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
                    be.notifyUpdate();
                    level.tickRateManager().setFrozen(true);
                    return null;
                });
                return;
            }
            if (!fixture.isDone()) return;
            fixture.join();
            if (!(mc.level.getBlockEntity(POS) instanceof EjectorBlockEntity be)) return;
            var data = (IEjectorDynamics) be;
            if (data.differentialcreate$launchedItems().isEmpty()) return;
            var item = data.differentialcreate$launchedItems().getFirst();
            var flight = data.differentialcreate$flight(item);
            if (flight == null || be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.EAST) return;
            if (flight.horizontal() != 8 || flight.vertical() != 0 || flight.facing() != Direction.SOUTH)
                throw new AssertionError("Client must receive the launch snapshot independently of the new configured target");
            var before = vertices(mc, be);
            be.setTarget(2, 12);
            var after = vertices(mc, be);
            if (before.size() < 24 || !before.equals(after)) throw new AssertionError("Retargeting changed the actual native BER's flying-item mesh");
            if (data.differentialcreate$charge().travel() != 0) throw new AssertionError("Released client cannot regain winding credit");
            // A server LAUNCHING update must not erase the local player's pending native bounce.
            be.setTarget(8, 0);
            mc.player.setPos(POS.getX() + .5, POS.getY() + .95, POS.getZ() + .5);
            mc.player.setDeltaMovement(Vec3.ZERO);
            be.activate();
            be.readClient(be.getUpdateTag(mc.level.registryAccess()), mc.level.registryAccess());
            be.tick();
            Vec3 actual = mc.player.getDeltaMovement();
            new EntityLauncher(8, 0).applyMotion(mc.player, Direction.EAST);
            if (actual.lengthSqr() == 0 || actual.distanceTo(mc.player.getDeltaMovement()) > 1e-12)
                throw new AssertionError("A server update cancelled or changed the native local-player bounce: " + actual);
            var virtual = new EjectorBlockEntity(be.getType(), POS.east(32), be.getBlockState());
            virtual.setLevel(mc.level);
            virtual.markVirtual();
            virtual.setSpeed(128);
            // The real Ponder world is not frozen; temporarily remove this fixture's client-only freeze.
            boolean frozen = mc.level.tickRateManager().isFrozen();
            try {
                mc.level.tickRateManager().setFrozen(false);
                virtual.tick(); virtual.tick(); virtual.tick();
                if (virtual.getState() != EjectorBlockEntity.State.CHARGED)
                    throw new AssertionError("Virtual Ponder ejector must retain its native winding clock: state=" + virtual.getState()
                        + ", speed=" + virtual.getSpeed() + ", lid=" + virtual.getLidProgress(1));
                virtual.activateDeferred();
                if (virtual.getState() != EjectorBlockEntity.State.LAUNCHING)
                    throw new AssertionError("Virtual Ponder launch must not require real shaft work");
            } finally { mc.level.tickRateManager().setFrozen(frozen); }
            DifferentialCreate.LOGGER.info("DC_EJECTOR_CLIENT_PASS: real flight packet retained target/facing; native BER emitted {} unchanged vertices after retargeting", before.size());
            done = true;
            mc.stop();
        } catch (Throwable failure) {
            DifferentialCreate.LOGGER.error("DC_EJECTOR_CLIENT_FAIL", failure);
            done = true;
            mc.stop();
        }
    }
    private static List<Vec3> vertices(Minecraft mc, EjectorBlockEntity be) {
        var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) throw new AssertionError("Missing native ejector BER");
        var vertices = new Vertices();
        renderer.render(be, .5f, new PoseStack(), ignored -> vertices, 0xf000f0, 0);
        return vertices.points;
    }
    private static final class Vertices implements VertexConsumer {
        private final List<Vec3> points = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { points.add(new Vec3(x, y, z)); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}

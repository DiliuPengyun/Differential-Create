package com.diliupengyun.differential_create.client;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Textured rubber hose and copper couplings; placement outlines are separate. */
public final class SteamHoseRenderer {
    private static final ResourceLocation HOSE = ResourceLocation.fromNamespaceAndPath("create", "block/hose");
    private static final ResourceLocation PIPES = ResourceLocation.fromNamespaceAndPath("create", "block/pipes");
    private static final RenderObservationCache<SteamEngineBlockEntity> ENGINES = new RenderObservationCache<>();
    private static final RenderObservationCache<AbstractBogeyBlockEntity> BOGEYS = new RenderObservationCache<>();
    private SteamHoseRenderer() {}

    public static void observe(SteamEngineBlockEntity engine) {
        var level = engine.getLevel();
        if (level != null && level.isClientSide && !engine.isVirtual()) ENGINES.observe(engine);
    }
    public static void observe(AbstractBogeyBlockEntity bogey) {
        var level = bogey.getLevel();
        if (level != null && level.isClientSide && level == Minecraft.getInstance().level) BOGEYS.observe(bogey);
    }
    public static AABB bounds(FluidTankBlockEntity part) {
        var tank = part.getControllerBE();
        if (tank == null) return new AABB(part.getBlockPos());
        var pos = tank.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + tank.getWidth(),
            pos.getY() + tank.getHeight(), pos.getZ() + tank.getWidth());
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) { ENGINES.clear(); BOGEYS.clear(); return; }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        var camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        for (var engine : ENGINES.snapshot(entry -> entry.isRemoved() || entry.getLevel() != mc.level)) renderWorld(pose, engine);
        for (var bogey : BOGEYS.snapshot(entry -> entry.isRemoved() || entry.getLevel() != mc.level)) {
            if (SteamBogey.powered(bogey)) renderWorld(pose, bogey);
        }
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof CarriageContraptionEntity carriage) || carriage.getContraption() == null) continue;
            var blocks = carriage.getContraption().getBlocks();
            var tanks = new ArrayList<AABB>();
            for (var block : blocks.values()) {
                if (!AllBlocks.FLUID_TANK.has(block.state()) || block.nbt() == null || !block.nbt().contains("Size")) continue;
                int width = Math.max(1, block.nbt().getInt("Size")), height = Math.max(1, block.nbt().getInt("Height"));
                var pos = block.pos();
                tanks.add(new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + width, pos.getY() + height, pos.getZ() + width));
            }
            for (var block : blocks.values()) {
                if (block.nbt() == null) continue;
                var data = block.nbt().contains(SteamHoseLinks.ENGINE_KEY) ? block.nbt() : block.nbt().getCompound("BogeyData");
                if (!data.contains(SteamHoseLinks.ENGINE_KEY)
                    || block.state().getBlock() instanceof AbstractBogeyBlock<?> && !data.getBoolean(SteamBogey.ENGINE)) continue;
                BlockPos end = block.pos().offset(BlockPos.of(data.getLong(SteamHoseLinks.ENGINE_KEY)));
                if (!blocks.containsKey(end) || carriage.getContraption().isHiddenInPortal(block.pos())
                    || carriage.getContraption().isHiddenInPortal(end)) continue;
                Vec3 selected = Vec3.atCenterOf(end);
                AABB tank = tanks.stream().filter(box -> box.contains(selected)).findFirst().orElseGet(() -> new AABB(end));
                var connection = SteamHoseGeometry.connect(block.state(), Vec3.atCenterOf(block.pos()), tank, selected)
                    .transform(point -> carriage.toGlobalVector(point, partial));
                draw(pose, connection, 0xFFFFFF);
            }
        }
        var preview = SteamHosePreview.connection();
        if (preview != null) draw(pose, preview, SteamHosePreview.valid() ? 0x9DE0AC : 0xEC8A82);
        pose.popPose();
    }

    private static void renderWorld(PoseStack pose, net.minecraft.world.level.block.entity.BlockEntity engine) {
        BlockPos offset = ((SteamHoseLinks.EngineLink) engine).differentialcreate$hose();
        var tank = SteamHoseLinks.tank(engine);
        if (offset == null || tank == null) return;
        draw(pose, SteamHoseGeometry.connect(engine.getBlockState(), Vec3.atCenterOf(engine.getBlockPos()),
            bounds(tank), Vec3.atCenterOf(engine.getBlockPos().offset(offset))), 0xFFFFFF);
    }

    private static void draw(PoseStack pose, SteamHoseGeometry.Connection connection, int color) {
        var mc = Minecraft.getInstance();
        var buffer = mc.renderBuffers().bufferSource().getBuffer(RenderType.cutoutMipped());
        var atlas = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        tube(pose, buffer, connection.points(), SteamHoseGeometry.RADIUS, atlas.apply(HOSE), 12, 16, 0, 16, color);
        for (var port : List.of(connection.engine(), connection.tank())) {
            var tip = port.point().add(port.normal().scale(.19));
            tube(pose, buffer, List.of(port.point(), tip), 2.25 / 16d, atlas.apply(PIPES), 1, 6, 11, 16, color);
            tube(pose, buffer, List.of(port.point().add(port.normal().scale(.025)),
                port.point().add(port.normal().scale(.07))), 2.75 / 16d, atlas.apply(PIPES), 1, 6, 11, 16, color);
        }
    }

    private static void tube(PoseStack pose, VertexConsumer buffer, List<Vec3> points, double radius,
        TextureAtlasSprite sprite, float u0, float u1, float v0, float v1, int color) {
        final int sides = 8;
        Vec3[][] rings = new Vec3[points.size()][sides], normals = new Vec3[points.size()][sides];
        int[] lights = new int[points.size()];
        Vec3 across = null;
        for (int i = 0; i < points.size(); i++) {
            lights[i] = LevelRenderer.getLightColor(Minecraft.getInstance().level, BlockPos.containing(points.get(i)));
            Vec3 tangent = points.get(Math.min(i + 1, points.size() - 1)).subtract(points.get(Math.max(0, i - 1))).normalize();
            if (across == null) across = Math.abs(tangent.y) < .95 ? new Vec3(0, 1, 0).cross(tangent).normalize()
                : new Vec3(1, 0, 0).cross(tangent).normalize();
            across = across.subtract(tangent.scale(across.dot(tangent))).normalize();
            Vec3 up = tangent.cross(across).normalize();
            for (int j = 0; j < sides; j++) {
                double angle = j * 2 * Math.PI / sides;
                normals[i][j] = across.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
                rings[i][j] = points.get(i).add(normals[i][j].scale(radius));
            }
        }
        double arc = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            double length = points.get(i).distanceTo(points.get(i + 1));
            double start = points.size() == 2 ? 0 : arc * 2 % 1;
            double end = points.size() == 2 ? 1 : Math.min(1, start + length * 2);
            float fromV = sprite.getV((float) (v0 + (v1 - v0) * start) / 16);
            float toV = sprite.getV((float) (v0 + (v1 - v0) * end) / 16);
            for (int j = 0; j < sides; j++) {
                int k = (j + 1) % sides;
                float fromU = sprite.getU(u0 / 16), toU = sprite.getU(u1 / 16);
                vertex(pose, buffer, rings[i][j], normals[i][j], fromU, fromV, color, lights[i]);
                vertex(pose, buffer, rings[i][k], normals[i][k], toU, fromV, color, lights[i]);
                vertex(pose, buffer, rings[i + 1][k], normals[i + 1][k], toU, toV, color, lights[i + 1]);
                vertex(pose, buffer, rings[i + 1][j], normals[i + 1][j], fromU, toV, color, lights[i + 1]);
            }
            arc += length;
        }
    }
    private static void vertex(PoseStack pose, VertexConsumer buffer, Vec3 point, Vec3 normal, float u, float v, int color, int light) {
        double shade = .8 + .2 * Math.max(0, normal.y) - .12 * Math.abs(normal.z);
        buffer.addVertex(pose.last().pose(), (float) point.x, (float) point.y, (float) point.z)
            .setColor((int) ((color >> 16 & 255) * shade), (int) ((color >> 8 & 255) * shade), (int) ((color & 255) * shade), 255)
            .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
            .setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
    }
}

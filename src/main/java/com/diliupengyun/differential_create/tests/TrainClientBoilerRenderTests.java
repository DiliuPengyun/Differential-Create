package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.trains.TrainStatePayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankMovementBehavior;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import io.netty.buffer.Unpooled;

/** Native virtual tank loading, actor ticks and emitted gauge geometry, including changing obstructions. */
public final class TrainClientBoilerRenderTests {
    private static final FluidTankMovementBehavior BEHAVIOR = new FluidTankMovementBehavior();
    private static final UUID PACKET_TRAIN = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private TrainClientBoilerRenderTests() {}

    public static void verify(Minecraft mc) {
        int cases = 0;
        for (int width : new int[]{1, 2, 3}) for (int attached : new int[]{0, 1, 2}) {
            for (int mask : new int[]{0, 1, 2, 4, 8, 15}) {
                int height = width == 1 ? 4 : 2;
                var cc = new CarriageContraption(Direction.SOUTH);
                cc.anchor = new BlockPos(1000, 70, 1000);
                cc.bounds = new AABB(-2, 0, -2, width + 2, height, width + 2);
                cc.entity = CarriageContraptionEntity.create(mc.level, cc);
                var boiler = new CompoundTag();
                boiler.putInt(attached == 2 ? "Whistles" : "Engines", attached == 0 ? 0 : 1);
                boiler.putInt("ActiveHeat", 2); boiler.putFloat("Supply", 20);
                for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < height; y++) {
                    var pos = new BlockPos(x, y, z);
                    var state = AllBlocks.FLUID_TANK.getDefaultState().setValue(FluidTankBlock.SHAPE, FluidTankBlock.Shape.PLAIN)
                        .setValue(FluidTankBlock.BOTTOM, y == 0).setValue(FluidTankBlock.TOP, y == height - 1);
                    var tag = new CompoundTag();
                    if (pos.equals(BlockPos.ZERO)) {
                        tag.putInt("Size", width); tag.putInt("Height", height); tag.putBoolean("Window", false); tag.put("Boiler", boiler.copy());
                    } else tag.put("Controller", NbtUtils.writeBlockPos(BlockPos.ZERO));
                    cc.getBlocks().put(pos, new StructureBlockInfo(pos, state, tag));
                }
                for (Direction side : Direction.Plane.HORIZONTAL) if (blocked(mask, side)) {
                    var at = obstruction(width, side);
                    cc.getBlocks().put(at, new StructureBlockInfo(at, Blocks.STONE.defaultBlockState(), null));
                }
                var client = cc.getOrCreateClientContraptionLazy();
                var tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
                if (attached > 0 && !vertices(mc, tank).isEmpty()) throw new AssertionError("Fixture did not reproduce initially hidden virtual boiler gauges");
                tank.boiler.gauge.startWithValue(0).chase(.8, .125, Chaser.EXP);
                var context = new MovementContext(mc.level, cc.getBlocks().get(BlockPos.ZERO), cc);
                BEHAVIOR.tick(context);
                if (attached == 0) {
                    if (tank.boiler.isActive() || !vertices(mc, tank).isEmpty() || tank.boiler.gauge.getValue(1) != 0)
                        throw new AssertionError("Moving ordinary tank gained boiler gauges");
                    cases++; continue;
                }
                verifyOcclusion(tank, mask);
                if (tank.boiler.gauge.getValue(1) <= 0) throw new AssertionError("Moving boiler gauge animation did not advance");
                var state = tank.getBlockState();
                var reference = (FluidTankBlockEntity) AllBlocks.FLUID_TANK.get().newBlockEntity(BlockPos.ZERO, state);
                if (reference == null) throw new AssertionError("Missing reference tank");
                reference.setLevel(mc.level); reference.handleUpdateTag(cc.getBlocks().get(BlockPos.ZERO).nbt(), mc.level.registryAccess());
                for (Direction side : Direction.Plane.HORIZONTAL) reference.boiler.occludedDirections[side.get2DDataValue()] = blocked(mask, side);
                reference.boiler.gauge.startWithValue(tank.boiler.gauge.getValue(.5f));
                var moving = vertices(mc, tank); var stationary = vertices(mc, reference);
                if (!moving.equals(stationary) || mask != 15 && moving.isEmpty())
                    throw new AssertionError("Moving boiler differs from native stationary gauge geometry: width=" + width + " mask=" + mask);
                cases++;
                if (mask == 0) cases += verifySupply(mc, cc, attached);
                if (mask != 0) {
                    for (Direction side : Direction.Plane.HORIZONTAL) if (blocked(mask, side))
                        client.getRenderLevel().setBlock(obstruction(width, side), Blocks.AIR.defaultBlockState(), 0);
                    BEHAVIOR.tick(context); verifyOcclusion(tank, 0);
                    if (vertices(mc, tank).isEmpty()) throw new AssertionError("Moving boiler gauges stayed hidden after an obstruction was removed");
                    cases++;
                }
                client.resetRenderLevel();
                var reloaded = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
                BEHAVIOR.tick(context); verifyOcclusion(reloaded, mask);
                if (!reloaded.boiler.isActive() || mask != 15 && vertices(mc, reloaded).isEmpty())
                    throw new AssertionError("Rebuilt moving boiler lost its native gauges");
                cases++;
            }
        }
        DifferentialCreate.LOGGER.info("DC_TRAIN_BOILER_RENDER_PASS: {} virtual tank, engine/whistle, occlusion, animation, native geometry, supply packet and rebuild checks", cases);
    }

    private static int verifySupply(Minecraft mc, CarriageContraption cc, int attached) {
        mc.level.addEntity(cc.entity);
        try {
            var sample = new TrainStatePayload.Boiler(BlockPos.ZERO, 1.2f, 4, false, attached == 1 ? 1 : 0, attached == 2 ? 1 : 0, .03, 1.2, 1);
            var payload = new TrainStatePayload(mc.level.dimension().location(), cc.entity.getId(), PACKET_TRAIN,
                1, true, 0, 0, 0, "", List.of(), List.of(sample));
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                TrainStatePayload.STREAM_CODEC.encode(buffer, payload);
                var decoded = TrainStatePayload.STREAM_CODEC.decode(buffer);
                if (!payload.equals(decoded) || buffer.isReadable()) throw new AssertionError("Boiler supply packet did not round-trip exactly");
                ClientTrainKinetics.apply(decoded);
            } finally { buffer.release(); }
            var client = cc.getOrCreateClientContraptionLazy();
            var tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
            var context = new MovementContext(mc.level, cc.getBlocks().get(BlockPos.ZERO), cc);
            if (tank.boiler.waterSupply != 1.2f || tank.boiler.activeHeat != 4 || BoilerAdmission.opening(tank.boiler) != .03
                || tank.boiler.getMaxHeatLevelForWaterSupply() != 4
                || cc.getBlocks().get(BlockPos.ZERO).nbt().getCompound("Boiler").getFloat("Supply") != 1.2f)
                throw new AssertionError("Moving boiler did not receive actual flow, admission and native equivalent water tier");
            client.resetRenderLevel();
            tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
            if (tank.boiler.waterSupply != 1.2f || tank.boiler.activeHeat != 4 || BoilerAdmission.opening(tank.boiler) != .03)
                throw new AssertionError("Recreated virtual boiler reverted to assembly-time supply");
            for (int i = 0; i < 10; i++) BEHAVIOR.tick(context);
            float suppliedGauge = tank.boiler.gauge.getValue(1);
            ClientTrainKinetics.apply(new TrainStatePayload(payload.dimension(), payload.entity(), PACKET_TRAIN, 1, true,
                0, 0, 0, "no_water", List.of(), List.of(new TrainStatePayload.Boiler(BlockPos.ZERO, 0, 4, false, sample.engines(), sample.whistles(), .03, 1.2, 0))));
            for (int i = 0; i < 10; i++) BEHAVIOR.tick(context);
            if (tank.boiler.waterSupply != 0 || tank.boiler.getMaxHeatLevelForWaterSupply() != 0
                || tank.boiler.gauge.getValue(1) >= suppliedGauge)
                throw new AssertionError("Dry moving boiler kept an outdated water tier or gauge target");
            var tooltip = new ArrayList<Component>(); tank.boiler.addToGoggleTooltip(tooltip, false, tank.getTotalTankSize());
            if (attached == 1 && tooltip.stream().noneMatch(c -> c.getString().contains("1.2")))
                throw new AssertionError("Moving boiler insufficient-water hint lost its actual cylinder demand");
            ClientTrainKinetics.apply(new TrainStatePayload(payload.dimension(), payload.entity(), PACKET_TRAIN, 1, true,
                0, .5, 0, "", List.of(), List.of(new TrainStatePayload.Boiler(BlockPos.ZERO, 20, 4, false, sample.engines(), sample.whistles(), .5, 20, 1))));
            var fullData = sample.data(); fullData.putFloat("Supply", 40); fullData.putDouble(BoilerAdmission.KEY, 1);
            var nativeBoiler = new BoilerData(); nativeBoiler.read(fullData, tank.getTotalTankSize());
            if (Math.abs(SourcePhysics.steamPeakPower(1024, tank.boiler, tank.getTotalTankSize())
                - .5 * SourcePhysics.steamPeakPower(1024, nativeBoiler, tank.getTotalTankSize())) > 1e-8)
                throw new AssertionError("Moving boiler diagnostics exceeded its admitted native engine power");
            ClientTrainKinetics.apply(new TrainStatePayload(payload.dimension(), payload.entity(), PACKET_TRAIN, 1, true,
                0, 0, 0, "", List.of(), List.of(new TrainStatePayload.Boiler(BlockPos.ZERO, 0, 4, false, sample.engines(), sample.whistles(), 0, 0, 0))));
            tooltip.clear(); tank.boiler.addToGoggleTooltip(tooltip, false, tank.getTotalTankSize());
            String closed = Component.translatable("gui.differentialcreate.boiler.admission_closed").getString();
            String noWater = Component.translatable("create.boiler.water_input_rate").getString();
            if (tooltip.stream().noneMatch(c -> c.getString().contains(closed))
                || tooltip.stream().anyMatch(c -> c.getString().contains(noWater)))
                throw new AssertionError("Closed admission was misreported as insufficient boiler water");
            client.resetRenderLevel(); tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
            if (BoilerAdmission.opening(tank.boiler) != 0 || tank.boiler.waterSupply != 0)
                throw new AssertionError("Recreated virtual boiler reopened closed admission");
            return 8;
        } finally { mc.level.removeEntity(cc.entity.getId(), Entity.RemovalReason.DISCARDED); }
    }

    private static boolean blocked(int mask, Direction side) { return (mask & 1 << side.get2DDataValue()) != 0; }
    private static BlockPos obstruction(int width, Direction side) {
        int centre = width / 2;
        return switch (side) {
            case NORTH -> new BlockPos(centre, 0, -1);
            case SOUTH -> new BlockPos(centre, 0, width);
            case WEST -> new BlockPos(-1, 0, centre);
            case EAST -> new BlockPos(width, 0, centre);
            default -> throw new IllegalArgumentException("Gauge side must be horizontal");
        };
    }
    private static void verifyOcclusion(FluidTankBlockEntity tank, int mask) {
        for (Direction side : Direction.Plane.HORIZONTAL)
            if (tank.boiler.occludedDirections[side.get2DDataValue()] != blocked(mask, side))
                throw new AssertionError("Wrong virtual gauge obstruction: width=" + tank.getWidth() + " mask=" + mask + " side=" + side);
    }
    private static List<Vertex> vertices(Minecraft mc, FluidTankBlockEntity tank) {
        var vertices = new Vertices(); var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(tank);
        if (renderer == null) throw new AssertionError("Missing native tank renderer");
        renderer.render(tank, .5f, new PoseStack(), type -> vertices, 0xf000f0, 0);
        vertices.flush(); return vertices.vertices;
    }
    private record Vertex(float x, float y, float z, float u, float v) {}
    private static final class Vertices implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private float x, y, z, u, v;
        private boolean pending;
        private void flush() { if (pending) vertices.add(new Vertex(x, y, z, u, v)); pending = false; }
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

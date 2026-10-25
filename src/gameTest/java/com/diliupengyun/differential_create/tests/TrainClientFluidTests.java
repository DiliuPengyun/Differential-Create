package com.diliupengyun.differential_create.tests;

import java.util.concurrent.CompletableFuture;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.contraption.storage.SyncedMountedStorage;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankMovementBehavior;
import com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Actual server inventory changes, native storage packets and virtual water-level animation. */
public final class TrainClientFluidTests {
    private static final int[] CONTENTS = {4000, 2000, 0, 1000, 4000};
    private static final FluidTankMovementBehavior BEHAVIOR = new FluidTankMovementBehavior();
    private static CompletableFuture<Long> change;
    private static int step, age, checks;
    private TrainClientFluidTests() {}

    public static void verify(Minecraft mc) {
        int cases = 0;
        for (int width : new int[]{1, 2, 3}) for (int height : new int[]{1, 3}) {
            var cc = new CarriageContraption(Direction.SOUTH);
            cc.anchor = new BlockPos(1000, 70, 1000);
            cc.bounds = new AABB(0, 0, 0, width, height, width);
            cc.entity = CarriageContraptionEntity.create(mc.level, cc);
            for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < height; y++) {
                var pos = new BlockPos(x, y, z);
                var state = AllBlocks.FLUID_TANK.getDefaultState().setValue(FluidTankBlock.SHAPE, FluidTankBlock.Shape.WINDOW)
                    .setValue(FluidTankBlock.BOTTOM, y == 0).setValue(FluidTankBlock.TOP, y == height - 1);
                var tag = new CompoundTag();
                if (pos.equals(BlockPos.ZERO)) {
                    tag.putInt("Size", width); tag.putInt("Height", height); tag.putBoolean("Window", true);
                } else tag.put("Controller", NbtUtils.writeBlockPos(BlockPos.ZERO));
                cc.getBlocks().put(pos, new StructureBlockInfo(pos, state, tag));
            }
            var info = cc.getBlocks().get(BlockPos.ZERO);
            var reference = (FluidTankBlockEntity) AllBlocks.FLUID_TANK.get().newBlockEntity(BlockPos.ZERO, info.state());
            if (reference == null) throw new AssertionError("Missing native reserve reference");
            reference.setLevel(mc.level); reference.handleUpdateTag(info.nbt(), mc.level.registryAccess());
            int capacity = reference.getTankInventory().getCapacity();
            reference.getTankInventory().setFluid(new FluidStack(Fluids.WATER, capacity * 3 / 4));
            FluidTankMountedStorage.fromTank(reference).afterSync(cc, BlockPos.ZERO);
            if (cc.getBlockEntityClientSide(BlockPos.ZERO) != null)
                throw new AssertionError("Native sync unexpectedly created a render world before its first frame");
            var client = cc.getOrCreateClientContraptionLazy();
            var tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
            if (tank.getTankInventory().getFluidAmount() != capacity * 3 / 4)
                throw new AssertionError("First rendered frame lost fluid synced before virtual tank creation");
            cases++;
            for (int amount : new int[]{capacity / 2, 0, capacity / 4, capacity}) {
                reference.getTankInventory().setFluid(new FluidStack(Fluids.WATER, amount));
                FluidTankMountedStorage.fromTank(reference).afterSync(cc, BlockPos.ZERO);
                var context = new MovementContext(mc.level, info, cc);
                for (int tick = 0; tick < 24; tick++) BEHAVIOR.tick(context);
                if (tank.getTankInventory().getFluidAmount() != amount
                    || Math.abs(tank.getFluidLevel().getValue(1) - amount / (float) capacity) > 1e-4)
                    throw new AssertionError("Native mounted inventory and animated fluid level disagree");
                var surface = new Surface();
                var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(tank);
                if (renderer == null) throw new AssertionError("Missing native fluid renderer");
                renderer.render(tank, 1, new PoseStack(), type -> surface, 0xf000f0, 0);
                double expected = .3125 + (height - .5625) * amount / capacity;
                if (amount == 0 ? surface.vertices != 0 : surface.vertices == 0 || Math.abs(surface.top - expected) > 1e-4)
                    throw new AssertionError("Rendered fluid surface disagrees with actual tank stock");
                client.resetRenderLevel(); tank = (FluidTankBlockEntity) client.getBlockEntity(BlockPos.ZERO);
                if (tank.getTankInventory().getFluidAmount() != amount || tank.getTotalTankSize() != width * width * height)
                    throw new AssertionError("Native tank rebuild lost synchronized fluid or multiblock dimensions");
                cases += 3;
            }
        }
        DifferentialCreate.LOGGER.info("DC_TRAIN_FLUID_RENDER_PASS: {} late creation, multiblock, native sync, animated surface and rebuild checks", cases);
    }

    private static final class Surface implements VertexConsumer {
        int vertices;
        float top = Float.NEGATIVE_INFINITY;
        @Override public VertexConsumer addVertex(float x, float y, float z) { vertices++; top = Math.max(top, y); return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    public static boolean tick(Minecraft mc, CarriageContraptionEntity entity, BlockPos reserve, BlockPos other) {
        if (step == CONTENTS.length) return true;
        var cc = entity.getContraption();
        var tank = (FluidTankBlockEntity) cc.getOrCreateClientContraptionLazy().getBlockEntity(reserve);
        var otherTank = (FluidTankBlockEntity) cc.getBlockEntityClientSide(other);
        if (tank == null || otherTank == null) throw new AssertionError("Missing assembled client reserve tanks");
        if (change == null) {
            int amount = CONTENTS[step];
            change = mc.getSingleplayerServer().submit(() -> {
                var level = mc.getSingleplayerServer().overworld();
                var nativeEntity = (CarriageContraptionEntity) level.getEntity(entity.getId());
                var storage = nativeEntity.getCarriage().storage.getFluids().storages.get(reserve);
                int old = storage.getFluidInTank(0).getAmount();
                if (old > amount) storage.drain(new FluidStack(Fluids.WATER, old - amount), FluidAction.EXECUTE);
                if (old < amount) storage.fill(new FluidStack(Fluids.WATER, amount - old), FluidAction.EXECUTE);
                if (storage.getFluidInTank(0).getAmount() != amount || old != amount && !((SyncedMountedStorage) storage).isDirty())
                    throw new AssertionError("Server reserve mutation did not mark native storage dirty");
                return level.getGameTime();
            });
            age = 0;
        }
        if (!change.isDone()) return false;
        change.join();
        BEHAVIOR.tick(new MovementContext(mc.level, cc.getBlocks().get(reserve), cc));
        if (tank.getTankInventory().getFluidAmount() != CONTENTS[step]
            || Math.abs(tank.getFluidLevel().getValue(1) - tank.getFillState()) > 1e-4) {
            if (++age > 600) throw new AssertionError("Synced reserve water level froze: step=" + step
                + " inventory=" + tank.getTankInventory().getFluidAmount() + " expected=" + CONTENTS[step]
                + " level=" + tank.getFluidLevel().getValue(1));
            return false;
        }
        if (otherTank.getTankInventory().getFluidAmount() != 1000)
            throw new AssertionError("Updating one reserve modified a different tank");
        checks += 3;
        cc.resetClientContraption();
        var rebuilt = (FluidTankBlockEntity) cc.getBlockEntityClientSide(reserve);
        if (rebuilt.getTankInventory().getFluidAmount() != CONTENTS[step])
            throw new AssertionError("Rebuilt reserve reverted to assembly water: step=" + step
                + " inventory=" + rebuilt.getTankInventory().getFluidAmount() + " expected=" + CONTENTS[step]);
        checks++; step++; change = null;
        if (step == CONTENTS.length)
            DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_FLUID_PASS: {} actual mounted tank sync, draining, empty/refill, animation and rebuild checks", checks);
        return step == CONTENTS.length;
    }
}

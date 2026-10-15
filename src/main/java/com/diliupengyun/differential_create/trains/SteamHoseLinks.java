package com.diliupengyun.differential_create.trains;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;

/** Relative endpoints survive translation; contraption disassembly rotates the offsets. */
public final class SteamHoseLinks {
    public static final String ENGINE_KEY = "DCSteamHose";
    public static final String TANK_KEY = "DCSteamEngines";
    public static final String WATER_KEY = "DCFeedWater";
    public static final int MAX_LENGTH = 48;
    public interface EngineLink {
        BlockPos differentialcreate$hose();
        void differentialcreate$hose(BlockPos offset);
        SteamHosePort differentialcreate$port();
        void differentialcreate$port(SteamHosePort port);
    }
    public interface TankLinks {
        Set<BlockPos> differentialcreate$engines();
        double differentialcreate$feedWater();
        void differentialcreate$feedWater(double amount);
    }
    private SteamHoseLinks() {}

    public static FluidTankBlockEntity tank(BlockEntity engine) {
        if (!(engine instanceof EngineLink link) || link.differentialcreate$port() == null
            || !(engine instanceof SteamEngineBlockEntity) && !SteamBogey.powered(engine)) return null;
        BlockPos offset = ((EngineLink) engine).differentialcreate$hose();
        if (offset == null || offset.distSqr(BlockPos.ZERO) > MAX_LENGTH * MAX_LENGTH) return null;
        var level = engine.getLevel();
        BlockPos at = engine.getBlockPos().offset(offset);
        if (level == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof FluidTankBlockEntity part)) return null;
        BlockPos controller = part.getController();
        return level.isLoaded(controller) && level.getBlockEntity(controller) instanceof FluidTankBlockEntity tank
            && tank.isController() && !tank.isRemoved() ? tank : null;
    }

    public static void unlink(BlockEntity engine) {
        BlockPos offset = ((EngineLink) engine).differentialcreate$hose();
        if (offset == null) return;
        var level = engine.getLevel();
        BlockPos pos = engine.getBlockPos().offset(offset);
        FluidTankBlockEntity old = null;
        if (level != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FluidTankBlockEntity part) {
            ((TankLinks) part).differentialcreate$engines().remove(offset.multiply(-1));
            changed(part); old = part.getControllerBE();
        }
        ((EngineLink) engine).differentialcreate$hose(null);
        changed(engine);
        if (old != null) old.updateBoilerState();
        if (engine instanceof SteamEngineBlockEntity nativeEngine) refreshDirectMount(nativeEngine);
    }
    /** A chunk unload or block replacement must leave the authoritative saved endpoint intact. */
    public static void detachIndex(BlockEntity engine) {
        var offset=((EngineLink)engine).differentialcreate$hose();var level=engine.getLevel();
        if(offset==null||level==null||level.isClientSide)return;
        var at=engine.getBlockPos().offset(offset);
        if(!level.isLoaded(at)||!(level.getBlockEntity(at) instanceof FluidTankBlockEntity part))return;
        ((TankLinks)part).differentialcreate$engines().remove(offset.multiply(-1));changed(part);
        var controller=part.getControllerBE();if(controller!=null)controller.updateBoilerState();
    }

    public static void connect(BlockEntity engine, FluidTankBlockEntity part) {
        connect(engine, part, new SteamHosePort(net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(.5, 1, .5)));
    }
    public static void connect(BlockEntity engine, FluidTankBlockEntity part, SteamHosePort port) {
        if (!(engine instanceof EngineLink) || !(engine instanceof SteamEngineBlockEntity) && !SteamBogey.powered(engine))
            throw new IllegalArgumentException("Steam hoses require an engine or upgraded bogey");
        java.util.Objects.requireNonNull(port, "Steam hoses require a boiler surface attachment");
        unlink(engine);
        BlockPos offset = part.getBlockPos().subtract(engine.getBlockPos());
        ((EngineLink) engine).differentialcreate$hose(offset);
        ((EngineLink) engine).differentialcreate$port(port);
        ((TankLinks) part).differentialcreate$engines().add(offset.multiply(-1));
        changed(engine);
        changed(part);
        if (engine instanceof SteamEngineBlockEntity nativeEngine) refreshDirectMount(nativeEngine);
        var controller = part.getControllerBE();
        if (controller != null) controller.updateBoilerState();
        var shaft = engine instanceof SteamEngineBlockEntity nativeEngine ? nativeEngine.getShaft() : null;
        if (shaft != null) com.diliupengyun.differential_create.kinetics.NetworkTopology.mark(shaft);
    }

    public static void refreshDirectMount(SteamEngineBlockEntity engine) {
        var level = engine.getLevel(); if (level == null) return;
        var at = engine.getBlockPos().relative(com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.getFacing(engine.getBlockState()).getOpposite());
        if (level.isLoaded(at) && level.getBlockEntity(at) instanceof FluidTankBlockEntity part) {
            var controller = part.getControllerBE(); if (controller != null) controller.updateBoilerState();
        }
    }

    /** Explicit hoses override backing tanks instead of activating two boilers. */
    public static int overriddenDirectCount(FluidTankBlockEntity controller) {
        var level = controller.getLevel(); if (level == null) return 0; int count = 0;
        for (int y = 0; y < controller.getHeight(); y++) for (int x = 0; x < controller.getWidth(); x++) for (int z = 0; z < controller.getWidth(); z++) {
            var pos = controller.getBlockPos().offset(x, y, z);
            if (!(level.getBlockState(pos).getBlock() instanceof com.simibubi.create.content.fluids.tank.FluidTankBlock)) continue;
            for (var side : net.minecraft.core.Direction.values()) {
                var at = pos.relative(side); if (!level.isLoaded(at)) continue;
                if (level.getBlockEntity(at) instanceof SteamEngineBlockEntity engine
                    && com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.getFacing(engine.getBlockState()) == side
                    && ((EngineLink) engine).differentialcreate$hose() != null && engine.getTank() != controller) count++;
            }
        }
        return count;
    }

    /** Tank-controller NBT may be swapped by Create when a multiblock rotates.
     * The engine endpoint is authoritative; tank backlinks are a recoverable index. */
    public static void ensureRegistered(BlockEntity engine) {
        if (!(engine instanceof EngineLink) || !(engine instanceof SteamEngineBlockEntity) && !SteamBogey.powered(engine)) return;
        var level = engine.getLevel();
        BlockPos offset = ((EngineLink) engine).differentialcreate$hose();
        if (level == null || level.isClientSide || engine.isRemoved() || offset == null || offset.distSqr(BlockPos.ZERO) > MAX_LENGTH * MAX_LENGTH) return;
        BlockPos at = engine.getBlockPos().offset(offset);
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof FluidTankBlockEntity part)) return;
        boolean added = ((TankLinks) part).differentialcreate$engines().add(offset.multiply(-1));
        if (added) changed(part);
        var controller = part.getControllerBE();
        if (controller != null && (added || !controller.boiler.isActive())) controller.updateBoilerState();
    }

    private static void changed(BlockEntity entity) {
        entity.setChanged();
        if (entity instanceof com.simibubi.create.foundation.blockEntity.SyncedBlockEntity synced) synced.notifyUpdate();
    }

    public static int remoteCount(FluidTankBlockEntity controller) {
        var level = controller.getLevel();
        if (level == null) return 0;
        var seen = new java.util.HashSet<BlockPos>();
        for (int y = 0; y < controller.getHeight(); y++)
            for (int x = 0; x < controller.getWidth(); x++) for (int z = 0; z < controller.getWidth(); z++) {
                BlockPos at = controller.getBlockPos().offset(x, y, z);
                if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof FluidTankBlockEntity part)) continue;
                for (BlockPos offset : ((TankLinks) part).differentialcreate$engines()) {
                    BlockPos enginePos = at.offset(offset);
                    if (!level.isLoaded(enginePos)) continue;
                    var engine = level.getBlockEntity(enginePos);
                    if (!(engine instanceof EngineLink) || !(engine instanceof SteamEngineBlockEntity) && !SteamBogey.powered(engine)) continue;
                    BlockPos link = ((EngineLink) engine).differentialcreate$hose();
                    if (link == null || !enginePos.offset(link).equals(at) || tank(engine) != controller) continue;
                    // A directly attached engine has already been counted by Create.
                    if (engine instanceof SteamEngineBlockEntity) {
                        BlockPos direct = enginePos.relative(com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock
                            .getFacing(engine.getBlockState()).getOpposite());
                        if (level.getBlockEntity(direct) instanceof FluidTankBlockEntity attached
                            && attached.getController().equals(controller.getBlockPos())) continue;
                    }
                    seen.add(enginePos);
                }
            }
        return seen.size();
    }
}

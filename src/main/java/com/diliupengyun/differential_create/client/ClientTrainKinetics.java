package com.diliupengyun.differential_create.client;

import java.util.*;
import net.minecraft.core.BlockPos;
import com.diliupengyun.differential_create.trains.TrainStatePayload;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ClientTrainKinetics {
    private record Sample(float rpm, double turns, double at) {}
    public record CarriageSample(List<TrainStatePayload.Rotor> rotors, double at) {}
    private static final Map<BlockEntity, Sample> ROTORS = new WeakHashMap<>();
    private static final Map<UUID, TrainStatePayload> TRAINS = new HashMap<>();
    private static final Map<CarriageContraptionEntity, CarriageSample> CARRIAGES = new WeakHashMap<>();
    private static final Map<SteamEngineBlockEntity, com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity> RENDER_SHAFTS = new WeakHashMap<>();
    private static Object lastLevel;
    private ClientTrainKinetics() {}
    public static TrainStatePayload state(UUID train) { checkLevel(); return TRAINS.get(train); }
    public static TrainStatePayload freshState(CarriageContraptionEntity entity) {
        checkLevel(); var sample = CARRIAGES.get(entity);
        if (sample == null || ClientKineticAngles.renderTick() - sample.at > 10) return null;
        return TRAINS.get(entity.trainId);
    }
    public static CarriageSample soundSample(CarriageContraptionEntity entity) { checkLevel(); return CARRIAGES.get(entity); }
    private static void checkLevel() {
        var level = Minecraft.getInstance().level;
        if (level != lastLevel) { ROTORS.clear(); TRAINS.clear(); CARRIAGES.clear(); RENDER_SHAFTS.clear(); lastLevel = level; }
    }
    public static void apply(TrainStatePayload p) {
        checkLevel(); var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(p.dimension())) return;
        TRAINS.put(p.train(), p);
        if (!(mc.level.getEntity(p.entity()) instanceof CarriageContraptionEntity entity) || entity.getContraption() == null) return;
        double at = ClientKineticAngles.renderTick();
        CARRIAGES.put(entity, new CarriageSample(p.rotors(), at));
        for (var boiler : p.boilers()) {
            var block = entity.getContraption().getBlocks().get(boiler.pos());
            if (block != null && block.nbt() != null) block.nbt().put("Boiler", boiler.data());
            if (entity.getContraption().getBlockEntityClientSide(boiler.pos()) instanceof FluidTankBlockEntity tank)
                tank.boiler.read(boiler.data(), tank.getTotalTankSize());
        }
        for (var rotor : p.rotors()) {
            var carriage = entity.getCarriage();
            if (carriage != null) for (var bogey : carriage.bogeys) {
                if (bogey == null || !bogey.bogeyData.getBoolean(com.diliupengyun.differential_create.trains.SteamBogey.ENGINE)
                    || !BlockPos.of(bogey.bogeyData.getLong(com.diliupengyun.differential_create.trains.SteamBogey.MOTOR_POS)).equals(rotor.pos())) continue;
                bogey.bogeyData.putDouble(com.diliupengyun.differential_create.trains.SteamBogey.RPM, rotor.rpm());
                bogey.bogeyData.putDouble(com.diliupengyun.differential_create.trains.SteamBogey.TURNS, rotor.turns());
                bogey.bogeyData.putDouble(com.diliupengyun.differential_create.trains.SteamBogey.SAMPLE, at);
            }
            var be = entity.getContraption().getBlockEntityClientSide(rotor.pos());
            if (be == null) continue;
            ROTORS.put(be, new Sample(rotor.rpm(), rotor.turns(), at));
            if (be instanceof KineticBlockEntity kinetic && kinetic.getSpeed() != rotor.rpm()) {
                float before = kinetic.getSpeed(); kinetic.setSpeed(rotor.rpm()); kinetic.onSpeedChanged(before);
            }
        }
        var carriage = entity.getCarriage();
        if (carriage != null && carriage.train != null) { carriage.train.speed = p.speed(); carriage.train.throttle = p.throttle(); }
    }
    public static Double theta(BlockEntity be) {
        checkLevel(); var sample = ROTORS.get(be); if (sample == null) return null;
        double age = Math.clamp(ClientKineticAngles.renderTick() - sample.at, 0, 10);
        return 360 * sample.turns + sample.rpm * .3 * age;
    }
    public static RotationFrame rotation(KineticBlockEntity be) {
        var theta = theta(be); if (theta == null) return null;
        int tick = (int) Math.floor(ClientKineticAngles.renderTick());
        double rate = ROTORS.get(be).rpm * .3;
        return new RotationFrame(tick, theta - (ClientKineticAngles.renderTick() - tick) * rate, rate);
    }
    /** Create mounts powered shafts as ordinary shafts. A render-only proxy keeps
     * its native cylinder model on the input rotor without adding a simulation source. */
    public static com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity renderShaft(SteamEngineBlockEntity engine) {
        if (!engine.isVirtual() || engine.getLevel() == null) return null;
        var at = com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.getShaftPos(engine.getBlockState(), engine.getBlockPos());
        var original = engine.getLevel().getBlockEntity(at);
        if (!(original instanceof KineticBlockEntity kinetic) || !ROTORS.containsKey(original)) return null;
        if (!com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock.isShaftValid(engine.getBlockState(), kinetic.getBlockState())) return null;
        var proxy = RENDER_SHAFTS.computeIfAbsent(engine, key -> {
            var state = com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlock.getEquivalent(kinetic.getBlockState());
            var created = new com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity(com.simibubi.create.AllBlockEntityTypes.POWERED_SHAFT.get(), at, state);
            created.setLevel(engine.getLevel()); created.markVirtual(); created.enginePos = at.subtract(engine.getBlockPos()); return created;
        });
        proxy.setSpeed(kinetic.getSpeed()); ROTORS.put(proxy, ROTORS.get(original)); return proxy;
    }
}

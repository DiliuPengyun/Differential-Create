package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.item.ItemStack;

public final class CarriageSteamState {
    public interface Holder {
        CarriageSteamState differentialcreate$steam();
        void differentialcreate$steam(CarriageSteamState state);
    }
    public TrainAssembly.Layout layout;
    public final Map<BlockPos, TrainMechanics.Engine> engines = new LinkedHashMap<>();
    public final Map<BlockPos, TrainMechanics.Engine> driveRotors = new HashMap<>();
    public final Map<BlockPos, MountedBurner> burners = new LinkedHashMap<>();
    public final Map<BlockPos, BoilerData> boilers = new LinkedHashMap<>();
    public final Map<BlockPos, Double> water = new HashMap<>();
    public final List<ItemStack> fuelReturns = new ArrayList<>();
    public double wheelTurns;
    public CarriageSteamState(TrainAssembly.Layout layout) {
        this.layout = layout;
        if (layout == null) return;
        for (var motor : layout.motors) engines.put(motor.pos(), new TrainMechanics.Engine());
        for (var boiler : layout.boilers.values()) {
            water.put(boiler.pos(), boiler.feedWater()); boilers.put(boiler.pos(), new BoilerData());
        }
        for (var boiler : layout.boilers.values()) for (var heater : boiler.heaters())
            if (heater.burner() != null) burners.putIfAbsent(heater.pos(), new MountedBurner(heater.burner(),
                heater.fixedHeat() < 0 ? HeatLevel.NONE : heater.fixedHeat() == 0 ? HeatLevel.SMOULDERING
                    : heater.fixedHeat() > 1 ? HeatLevel.SEETHING : HeatLevel.KINDLED));
    }
    public void apply(CarriageContraption contraption, int gear, boolean sendChanges, boolean gearControlled) {
        if (layout == null) return;
        for (var motor : layout.motors) {
            var block = contraption.getBlocks().get(motor.pos());
            var engine = engines.get(motor.pos());
            if (block == null || block.nbt() == null || engine == null) continue;
            var data = block.nbt().getCompound("BogeyData");
            data.putDouble(SteamBogey.RPM, engine.rpm()); data.putDouble(SteamBogey.TURNS, engine.turns);
        }
        for (var entry : water.entrySet()) {
            var block = contraption.getBlocks().get(entry.getKey()); if (block == null) continue;
            var tag = block.nbt() == null ? new CompoundTag() : block.nbt();
            tag.putDouble(SteamHoseLinks.WATER_KEY, entry.getValue());
            var boiler = boilers.get(entry.getKey());
            if (boiler != null) {
                boiler.attachedWhistles = tag.getCompound("Boiler").getInt("Whistles");
                tag.put("Boiler", boiler.write());
            }
            contraption.getBlocks().put(block.pos(), new StructureBlockInfo(block.pos(), block.state(), tag));
        }
        for (var entry : burners.entrySet()) {
            var block = contraption.getBlocks().get(entry.getKey());
            if (block == null || !block.state().hasProperty(BlazeBurnerBlock.HEAT_LEVEL)) continue;
            var tag = block.nbt() == null ? new CompoundTag() : block.nbt(); entry.getValue().write(tag);
            var state = block.state().setValue(BlazeBurnerBlock.HEAT_LEVEL, entry.getValue().heat());
            var updated = new StructureBlockInfo(block.pos(), state, tag);
            if (sendChanges && state != block.state() && contraption.entity != null) contraption.entity.setBlock(block.pos(), updated);
            else contraption.getBlocks().put(block.pos(), updated);
        }
        for (BlockPos at : layout.levers) {
            var block = contraption.getBlocks().get(at);
            if (block == null || !block.state().hasProperty(GearLeverBlock.GEAR)) continue;
            var state = block.state().setValue(GearLeverBlock.GEAR, TrainMechanics.clampGear(gear));
            if (state == block.state()) continue;
            var updated = new StructureBlockInfo(at, state, block.nbt());
            if (sendChanges && contraption.entity != null) contraption.entity.setBlock(at, updated);
            else contraption.getBlocks().put(at, updated);
        }
    }
    public void apply(Carriage carriage, int gear) {
        for (var bogey : carriage.bogeys) {
            if (bogey == null || !bogey.bogeyData.getBoolean(SteamBogey.ENGINE)) continue;
            var engine = engines.get(BlockPos.of(bogey.bogeyData.getLong(SteamBogey.MOTOR_POS)));
            if (engine != null) {
                bogey.bogeyData.putDouble(SteamBogey.RPM, engine.rpm());
                bogey.bogeyData.putDouble(SteamBogey.TURNS, engine.turns);
            }
        }
        carriage.forEachPresentEntity(entity -> {
            if (entity.getContraption() instanceof CarriageContraption cc) apply(cc, gear, !com.diliupengyun.differential_create.kinetics.PhysicsPreview.enabled(entity.level()),
                carriage.train != null && TrainPhysics.hasLever(carriage.train));
        });
    }
    public CompoundTag write(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        if (layout != null) tag.put("Layout", layout.write());
        tag.putDouble("WheelTurns", wheelTurns); var list = new ListTag();
        for (var entry : engines.entrySet()) {
            var t = new CompoundTag(); var e = entry.getValue(); t.putLong("Pos", entry.getKey().asLong());
            t.putDouble("Omega", e.omega); t.putDouble("Turns", e.turns); t.putDouble("Ratio", e.ratio);
            t.putDouble("ShiftTime", e.shiftTime); t.putDouble("ClutchHeat", e.clutchHeat); list.add(t);
        }
        tag.put("Engines", list); list = new ListTag();
        for (var entry : burners.entrySet()) { var t = new CompoundTag(); t.putLong("Pos", entry.getKey().asLong()); entry.getValue().write(t); list.add(t); }
        tag.put("Burners", list); list = new ListTag();
        for (var entry : water.entrySet()) { var t = new CompoundTag(); t.putLong("Pos", entry.getKey().asLong()); t.putDouble("Water", entry.getValue()); list.add(t); }
        tag.put("FeedWater", list); list = new ListTag();
        for (var entry : boilers.entrySet()) {
            var t = entry.getValue().write(); t.putLong("Pos", entry.getKey().asLong()); list.add(t);
        }
        tag.put("Boilers", list); list = new ListTag();
        for (var stack : fuelReturns) if (!stack.isEmpty()) list.add(stack.save(registries));
        tag.put("FuelReturns", list); return tag;
    }
    public static CarriageSteamState read(CompoundTag tag, HolderLookup.Provider registries) {
        var state = new CarriageSteamState(tag.contains("Layout") ? TrainAssembly.Layout.read(tag.getCompound("Layout")) : null);
        state.wheelTurns = finite(tag.getDouble("WheelTurns"));
        for (Tag raw : tag.getList("Engines", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) raw; var e = state.engines.get(BlockPos.of(t.getLong("Pos"))); if (e == null) continue;
            e.omega = finite(t.getDouble("Omega")); e.turns = finite(t.getDouble("Turns")); e.ratio = finite(t.getDouble("Ratio"));
            e.shiftTime = Math.clamp(finite(t.getDouble("ShiftTime")), 0, .35); e.clutchHeat = Math.max(0, finite(t.getDouble("ClutchHeat")));
        }
        for (Tag raw : tag.getList("Burners", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) raw; BlockPos at = BlockPos.of(t.getLong("Pos"));
            var old = state.burners.get(at); if (old != null) state.burners.put(at, new MountedBurner(t, old.heat()));
        }
        for (Tag raw : tag.getList("FeedWater", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) raw; BlockPos at = BlockPos.of(t.getLong("Pos"));
            if (state.layout != null && state.layout.boilers.containsKey(at)) state.water.put(at, Math.clamp(finite(t.getDouble("Water")), 0, 1));
        }
        for (Tag raw : tag.getList("Boilers", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) raw; BlockPos at = BlockPos.of(t.getLong("Pos"));
            var boiler = state.boilers.get(at);
            if (boiler != null) boiler.read(t, state.layout.boilers.get(at).size());
        }
        for (Tag raw : tag.getList("FuelReturns", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(registries, (CompoundTag) raw); if (!stack.isEmpty()) state.fuelReturns.add(stack);
        }
        return state;
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}

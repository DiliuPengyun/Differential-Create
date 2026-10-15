package com.diliupengyun.differential_create.trains;

import java.util.*;
import com.diliupengyun.differential_create.content.ModContent;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.boiler.BoilerHeater;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

/** Only installed, hose-fed bogey engines produce traction. The transmission is internal. */
public final class TrainAssembly {
    public static final String KEY = "DCTrainLayout";
    public interface LayoutHolder {
        Layout differentialcreate$layout();
        void differentialcreate$layout(Layout layout);
    }
    // The internal transmission and motor share the powered bogey's position.
    public record Motor(BlockPos pos, BlockPos shaft, BlockPos boiler, double capacity) {}
    public record Heater(BlockPos pos, float fixedHeat, CompoundTag burner) {}
    public record Boiler(BlockPos pos, int size, List<Heater> heaters, double feedWater) {}
    public record Drive(Motor motor, double ratio, BlockPos controller, double dial) {}
    public static final class Layout {
        public int blocks, frontalArea, initialGear = 1, installedEngines;
        public BlockPos anchor = BlockPos.ZERO;
        public Direction direction = Direction.SOUTH;
        public final List<BlockPos> levers = new ArrayList<>(), wheels = new ArrayList<>();
        public final List<Motor> motors = new ArrayList<>();
        public final Map<BlockPos, Boiler> boilers = new LinkedHashMap<>();
        public final List<Drive> drives = new ArrayList<>();
        public final Set<Long> frontalProjection = new HashSet<>();
        public String failure = "no_engine";

        public void resolve() {
            resolve(motor -> boilers.containsKey(motor.boiler));
        }
        public void resolve(java.util.function.Predicate<Motor> supplied) {
            drives.clear();
            for (var motor : motors)
                if (motor.pos.equals(motor.shaft) && wheels.contains(motor.pos) && supplied.test(motor))
                    drives.add(new Drive(motor, 1, motor.pos, TrainMechanics.GEARS[1]));
            failure = !drives.isEmpty() ? "ready" : installedEngines > 0 ? "no_steam_connection" : "no_engine";
        }
        public CompoundTag write() {
            var tag = new CompoundTag();
            tag.putInt("InstalledEngines", installedEngines);
            tag.putLong("Anchor", anchor.asLong());
            tag.putInt("Blocks", blocks); tag.putInt("Gear", initialGear);
            tag.putLongArray("Projection", frontalProjection.stream().mapToLong(Long::longValue).sorted().toArray());
            tag.putString("Direction", direction.getName());
            tag.putLongArray("Levers", levers.stream().mapToLong(BlockPos::asLong).toArray());
            tag.putLongArray("Wheels", wheels.stream().mapToLong(BlockPos::asLong).toArray());
            var list = new ListTag();
            for (var motor : motors) {
                var t = new CompoundTag(); t.putLong("Pos", motor.pos.asLong()); t.putLong("Shaft", motor.shaft.asLong());
                t.putLong("Boiler", motor.boiler.asLong()); t.putDouble("Capacity", motor.capacity); list.add(t);
            }
            tag.put("Motors", list); list = new ListTag();
            for (var boiler : boilers.values()) {
                var t = new CompoundTag(); t.putLong("Pos", boiler.pos.asLong()); t.putInt("Size", boiler.size);
                var heaters = new ListTag();
                for (var heater : boiler.heaters) {
                    var h = new CompoundTag(); h.putLong("Pos", heater.pos.asLong()); h.putFloat("Heat", heater.fixedHeat);
                    if (heater.burner != null) h.put("Burner", heater.burner.copy()); heaters.add(h);
                }
                t.put("Heaters", heaters); t.putDouble("FeedWater", boiler.feedWater); list.add(t);
            }
            tag.put("Boilers", list); return tag;
        }
        public static Layout read(CompoundTag tag) {
            var l = new Layout();
            l.anchor = BlockPos.of(tag.getLong("Anchor"));
            l.blocks = Math.max(1, tag.getInt("Blocks"));
            for (long cell : tag.getLongArray("Projection")) l.frontalProjection.add(cell);
            l.frontalArea = l.frontalProjection.size();
            l.installedEngines = Math.max(0, tag.getInt("InstalledEngines"));
            l.initialGear = TrainMechanics.clampGear(tag.getInt("Gear"));
            l.direction = Optional.ofNullable(Direction.byName(tag.getString("Direction"))).orElse(Direction.SOUTH);
            for (long p : tag.getLongArray("Levers")) l.levers.add(BlockPos.of(p));
            for (long p : tag.getLongArray("Wheels")) l.wheels.add(BlockPos.of(p));
            for (Tag raw : tag.getList("Motors", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag) raw;
                double capacity = t.getDouble("Capacity");
                if (Double.isFinite(capacity) && capacity > 0)
                    l.motors.add(new Motor(BlockPos.of(t.getLong("Pos")), BlockPos.of(t.getLong("Shaft")),
                        BlockPos.of(t.getLong("Boiler")), capacity));
            }
            for (Tag raw : tag.getList("Boilers", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag) raw; var heaters = new ArrayList<Heater>();
                for (Tag h : t.getList("Heaters", Tag.TAG_COMPOUND)) {
                    var ht = (CompoundTag) h;
                    heaters.add(new Heater(BlockPos.of(ht.getLong("Pos")), ht.getFloat("Heat"),
                        ht.contains("Burner") ? ht.getCompound("Burner").copy() : null));
                }
                BlockPos at = BlockPos.of(t.getLong("Pos"));
                double water = t.getDouble("FeedWater");
                l.boilers.put(at, new Boiler(at, Math.max(1, t.getInt("Size")), heaters,
                    Double.isFinite(water) ? Math.clamp(water, 0, 1) : 0));
            }
            l.resolve(); return l;
        }
    }
    private TrainAssembly() {}
    public static Layout capture(Level level, CarriageContraption contraption) {
        var result = new Layout(); result.anchor = contraption.anchor;
        result.blocks = contraption.getBlocks().size(); result.direction = contraption.getAssemblyDirection();
        for (var block : contraption.getBlocks().values()) {
            BlockPos local = block.pos(), world = local.offset(contraption.anchor);
            if (!block.state().isAir()) {
                int lateral = result.direction.getStepZ() * local.getX() - result.direction.getStepX() * local.getZ();
                result.frontalProjection.add(TrainAerodynamics.cell(lateral, local.getY()));
            }
            if (ModContent.GEAR_LEVER.has(block.state())) {
                if (result.levers.isEmpty()) result.initialGear = block.state().getValue(GearLeverBlock.GEAR);
                result.levers.add(local);
            }
            if (level.getBlockEntity(world) instanceof com.simibubi.create.content.fluids.tank.FluidTankBlockEntity tank
                && tank.isController() && tank.boiler.isActive()) {
                var heaters = new ArrayList<Heater>();
                for (int x = 0; x < tank.getWidth(); x++) for (int z = 0; z < tank.getWidth(); z++) {
                    BlockPos at = local.offset(x, -1, z); var heat = contraption.getBlocks().get(at);
                    if (heat == null) continue;
                    BlockPos heatWorld = at.offset(contraption.anchor);
                    var burner = level.getBlockEntity(heatWorld) instanceof BlazeBurnerBlockEntity be
                        ? be.saveWithFullMetadata(level.registryAccess()) : null;
                    heaters.add(new Heater(at, BoilerHeater.findHeat(level, heatWorld, heat.state()), burner));
                }
                result.boilers.put(local, new Boiler(local, tank.getTotalTankSize(), heaters,
                    ((SteamHoseLinks.TankLinks) tank).differentialcreate$feedWater()));
            }
            if (!(level.getBlockEntity(world) instanceof AbstractBogeyBlockEntity bogey)) continue;
            result.wheels.add(local);
            if (!SteamBogey.powered(bogey)) continue;
            result.installedEngines++;
            var tank = SteamHoseLinks.tank(bogey);
            if (tank == null) continue;
            BlockPos boiler = tank.getBlockPos().subtract(contraption.anchor);
            result.motors.add(new Motor(local, local, boiler, BlockStressValues.getCapacity(AllBlocks.STEAM_ENGINE.get())));
        }
        result.frontalArea = result.frontalProjection.size(); result.resolve(); return result;
    }
}

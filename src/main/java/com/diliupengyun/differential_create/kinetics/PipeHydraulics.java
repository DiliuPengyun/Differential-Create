package com.diliupengyun.differential_create.kinetics;

import java.util.EnumMap;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;

/** State belongs to the existing pipe, not to a transient graph or a new tank. */
public final class PipeHydraulics {
    public FluidStack fluid = FluidStack.EMPTY;
    public final EnumMap<Direction, Double> outward = new EnumMap<>(Direction.class);
    public final EnumMap<Direction, Buffer> buffers = new EnumMap<>(Direction.class);
    public final EnumMap<Direction, PipeContents> contents = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, PipeContents> previous = new EnumMap<>(Direction.class);
    public PipeContents core = PipeContents.EMPTY;
    public long lastVisualHash = Long.MIN_VALUE;
    private int visualAge = 1;
    public double pumpFlow;
    public Direction pumpFacing;
    public double pumpAxisSign = 1;

    public PipeContents content(Direction side) { return contents.getOrDefault(side, PipeContents.EMPTY); }
    public PipeContents content(Direction side, double partial) {
        return visualAge > 0 ? content(side) : content(side).interpolate(previous.getOrDefault(side, content(side)), partial);
    }
    public void tickVisual() { visualAge = 1; }
    public double volume() {
        double length = pumpFacing == null ? .5 : .25, volume = core.fraction() * .5;
        for (var arm : contents.values()) volume += arm.fraction() * length;
        return volume * PipeFilling.MB_PER_METRE;
    }
    public long visualHash() {
        long hash = fluid.isEmpty() ? 0 : 31L * fluid.getFluid().hashCode() + fluid.getComponentsPatch().hashCode();
        for (Direction side : Direction.values()) {
            hash = hash * 31 + content(side).visualHash();
            hash = hash * 31 + Math.round(outward.getOrDefault(side, 0d) * 100);
        }
        return hash * 31 + core.visualHash();
    }

    /** Actual owned sub-mB portions bridge integer inventory capabilities.
     * Source extraction rounds up into ownership; output delivery rounds down. */
    public static final class Buffer {
        public FluidStack fluid = FluidStack.EMPTY;
        public double amount;
        public boolean delivery;
        public boolean matches(FluidStack other) {
            return amount == 0 || !fluid.isEmpty() && FluidStack.isSameFluidSameComponents(fluid, other);
        }
        public void add(FluidStack identity, double volume) {
            if (!matches(identity)) throw new IllegalStateException("Conflicting owned pipe fluid");
            fluid = identity.copyWithAmount(1); amount += volume;
            if (amount < -1e-9 || !Double.isFinite(amount)) throw new IllegalStateException("Invalid pipe ownership");
            if (amount < 0) amount = 0;
            if (amount == 0) delivery = false;
        }
    }

    public void write(CompoundTag parent, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ContentsVersion", 1);
        tag.put("Fluid", fluid.saveOptional(registries));
        tag.put("Core", saveContents(core));
        tag.putDouble("PumpFlow", pumpFlow);
        tag.putDouble("AxisSign", pumpAxisSign);
        if (pumpFacing != null) tag.putString("Facing", pumpFacing.getName());
        for (Direction side : Direction.values()) {
            CompoundTag port = new CompoundTag();
            port.putDouble("Flow", outward.getOrDefault(side, 0d));
            port.put("Contents", saveContents(content(side)));
            Buffer buffer = buffers.get(side);
            if (buffer != null && buffer.amount > 0) {
                port.putDouble("Owned", buffer.amount); port.put("Fluid", buffer.fluid.saveOptional(registries));
                port.putBoolean("Delivery", buffer.delivery);
            }
            tag.put(side.getName(), port);
        }
        parent.put("DCIdealFluid", tag);
    }

    public void read(CompoundTag parent, HolderLookup.Provider registries) {
        previous.clear(); previous.putAll(contents);
        visualAge = 0;
        outward.clear(); buffers.clear(); contents.clear(); core = PipeContents.EMPTY;
        pumpFlow = 0; pumpFacing = null; fluid = FluidStack.EMPTY;
        if (!parent.contains("DCIdealFluid")) return;
        CompoundTag tag = parent.getCompound("DCIdealFluid");
        fluid = FluidStack.parseOptional(registries, tag.getCompound("Fluid"));
        boolean ownedContents = tag.getInt("ContentsVersion") == 1;
        if (ownedContents) { pumpFlow = finite(tag.getDouble("PumpFlow")); core = loadContents(tag.getList("Core", Tag.TAG_DOUBLE)); }
        pumpFacing = Direction.byName(tag.getString("Facing"));
        pumpAxisSign = tag.getDouble("AxisSign") < 0 ? -1 : 1;
        for (Direction side : Direction.values()) if (tag.contains(side.getName())) {
            CompoundTag port = tag.getCompound(side.getName());
            outward.put(side, ownedContents ? finite(port.getDouble("Flow")) : 0d);
            if (ownedContents) contents.put(side, loadContents(port.getList("Contents", Tag.TAG_DOUBLE)));
            FluidStack identity = FluidStack.parseOptional(registries, port.getCompound("Fluid"));
            double amount = finite(port.getDouble("Owned"));
            if (amount > 0 && !identity.isEmpty()) {
                Buffer buffer = new Buffer(); buffer.fluid = identity; buffer.amount = amount;
                buffer.delivery = port.getBoolean("Delivery"); buffers.put(side, buffer);
            }
        }
    }
    private static ListTag saveContents(PipeContents contents) {
        ListTag result = new ListTag();
        for (double end : contents.data()) result.add(DoubleTag.valueOf(end));
        return result;
    }
    private static PipeContents loadContents(ListTag tag) {
        double[] ends = new double[tag.size()];
        for (int i = 0; i < ends.length; i++) ends[i] = tag.getDouble(i);
        return PipeContents.of(ends);
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}

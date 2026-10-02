package com.diliupengyun.differential_create.kinetics;

import java.util.EnumMap;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;

/** State belongs to the existing pipe, not to a transient graph or a new tank. */
public final class PipeHydraulics {
    public FluidStack fluid = FluidStack.EMPTY;
    public final EnumMap<Direction, Double> outward = new EnumMap<>(Direction.class);
    public final EnumMap<Direction, Buffer> buffers = new EnumMap<>(Direction.class);
    public double pumpFlow;
    public Direction pumpFacing;
    public double pumpAxisSign = 1;

    /** Actual owned sub-mB portions bridge integer inventory capabilities.
     * Source extraction rounds up into ownership; output delivery rounds down. */
    public static final class Buffer {
        public FluidStack fluid = FluidStack.EMPTY;
        public double amount;
        public boolean matches(FluidStack other) {
            return amount == 0 || !fluid.isEmpty() && FluidStack.isSameFluidSameComponents(fluid, other);
        }
        public void add(FluidStack identity, double volume) {
            if (!matches(identity)) throw new IllegalStateException("Conflicting owned pipe fluid");
            fluid = identity.copyWithAmount(1); amount += volume;
            if (amount < -1e-9 || !Double.isFinite(amount)) throw new IllegalStateException("Invalid pipe ownership");
            if (amount < 0) amount = 0;
        }
    }

    public void write(CompoundTag parent, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Fluid", fluid.saveOptional(registries));
        tag.putDouble("PumpFlow", pumpFlow);
        tag.putDouble("AxisSign", pumpAxisSign);
        if (pumpFacing != null) tag.putString("Facing", pumpFacing.getName());
        for (Direction side : Direction.values()) {
            CompoundTag port = new CompoundTag();
            port.putDouble("Flow", outward.getOrDefault(side, 0d));
            Buffer buffer = buffers.get(side);
            if (buffer != null && buffer.amount > 0) {
                port.putDouble("Owned", buffer.amount); port.put("Fluid", buffer.fluid.saveOptional(registries));
            }
            tag.put(side.getName(), port);
        }
        parent.put("DCIdealFluid", tag);
    }

    public void read(CompoundTag parent, HolderLookup.Provider registries) {
        outward.clear(); buffers.clear(); pumpFlow = 0; pumpFacing = null; fluid = FluidStack.EMPTY;
        if (!parent.contains("DCIdealFluid")) return;
        CompoundTag tag = parent.getCompound("DCIdealFluid");
        fluid = FluidStack.parseOptional(registries, tag.getCompound("Fluid"));
        pumpFlow = finite(tag.getDouble("PumpFlow")); pumpFacing = Direction.byName(tag.getString("Facing"));
        pumpAxisSign = tag.getDouble("AxisSign") < 0 ? -1 : 1;
        for (Direction side : Direction.values()) if (tag.contains(side.getName())) {
            CompoundTag port = tag.getCompound(side.getName());
            outward.put(side, finite(port.getDouble("Flow")));
            FluidStack identity = FluidStack.parseOptional(registries, port.getCompound("Fluid"));
            double amount = finite(port.getDouble("Owned"));
            if (amount > 0 && !identity.isEmpty()) {
                Buffer buffer = new Buffer(); buffer.fluid = identity; buffer.amount = amount; buffers.put(side, buffer);
            }
        }
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}

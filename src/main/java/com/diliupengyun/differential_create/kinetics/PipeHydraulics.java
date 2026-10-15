package com.diliupengyun.differential_create.kinetics;

import java.util.EnumMap;
import com.simibubi.create.content.fluids.PipeConnection;
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
    /** Native endpoint stock surviving removal of its physical connection. */
    public final EnumMap<Direction, PipeConnection> retainedPorts = new EnumMap<>(Direction.class);
    public final EnumMap<Direction, PipeContents> contents = new EnumMap<>(Direction.class);
    public final EnumMap<Direction, double[]> intervalFlows = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, PipeContents> previous = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, Double> previousOutward = new EnumMap<>(Direction.class);
    public PipeContents core = PipeContents.EMPTY;
    public double[] coreFlows = new double[0];
    public long lastVisualHash = Long.MIN_VALUE;
    private int visualAge = 1;
    public double pumpFlow;
    public Direction pumpFacing;
    public double macroLiquid = Double.NaN;
    /** EMPTY identity is owned virtual air only inside this queue. */
    public FluidQueue<FluidStack> phases;
    public Direction queueFront;
    public int splitCursor;
    public double queueCapacity;
    private PipeContents macroCore;
    private final EnumMap<Direction, PipeContents> macroArms = new EnumMap<>(Direction.class);

    public PipeContents content(Direction side) { return contents.getOrDefault(side, PipeContents.EMPTY); }
    public PipeMotion motion(Direction side) {
        var liquid = content(side); var flows = intervalFlows.get(side);
        return flows != null && flows.length == liquid.intervals() ? new PipeMotion(liquid, flows)
            : PipeMotion.uniform(liquid, outward.getOrDefault(side, 0d));
    }
    public PipeMotion coreMotion() {
        return coreFlows.length == core.intervals() ? new PipeMotion(core, coreFlows) : PipeMotion.uniform(core, pumpFlow);
    }
    public void motion(Direction side, PipeMotion motion) {
        contents.put(side, motion.contents()); intervalFlows.put(side, motion.flow());
    }
    public PipeContents content(Direction side, double partial) {
        return visualAge > 0 ? content(side) : content(side).interpolate(previous.getOrDefault(side, content(side)), partial);
    }
    /** Client presentation uses the same interpolation window as the water
     * intervals; the authoritative outward map remains untouched. */
    public double visualFlow(Direction side, double partial) {
        double current = outward.getOrDefault(side, 0d);
        if (visualAge > 0) return current;
        double old = previousOutward.getOrDefault(side, current);
        return old + Math.max(0, Math.min(1, partial)) * (current - old);
    }
    public void tickVisual() { visualAge = 1; }
    public double volume() {
        if (phases != null) return macroLiquid;
        if (Double.isFinite(macroLiquid) && macroCore == core && macroArms.equals(contents)) return macroLiquid;
        double length = pumpFacing == null ? .5 : .25, volume = core.fraction() * .5;
        for (var arm : contents.values()) volume += arm.fraction() * length;
        double actual = FluidBudget.receiptSpace(volume * PipeFilling.MB_PER_METRE, 0);
        if (Double.isFinite(macroLiquid)) { macroLiquid = actual; rememberMacroGeometry(); }
        return actual;
    }
    /** Initialise the aggregate volume without creating liquid or copying external inventory. */
    public void prepareMacro() {
        if (Double.isFinite(macroLiquid)) { volume(); return; }
        macroLiquid = volume();
        for (var side : intervalFlows.keySet()) outward.put(side, motion(side).average());
        if (coreFlows.length == core.intervals() && coreFlows.length > 0) pumpFlow = coreMotion().average();
        rememberMacroGeometry();
    }
    public void adoptMacro(double volume, boolean pump, java.util.Set<Direction> sides, double surface) {
        phases = null; // explicit state initialisation, not a live refill
        // Body inventory and endpoint ownership share one numerical zero.
        // Normalise at commit/read so cached volume queries stay cheap.
        macroLiquid = FluidBudget.receiptSpace(volume, 0);
        double capacity = (pump ? .5 : 0) + sides.size() * (pump ? .25 : .5);
        double fraction = capacity == 0 ? 0 : Math.min(1, macroLiquid / (capacity * PipeFilling.MB_PER_METRE));
        var display = fraction == 0 ? PipeContents.EMPTY : fraction >= 1 ? PipeContents.FULL : PipeContents.of(0, fraction);
        contents.clear(); intervalFlows.clear();
        double arm = pump ? .25 : .5;
        for (var side : sides) {
            PipeContents water = side == Direction.UP ? belowPlane(0, arm, surface)
                : side == Direction.DOWN ? belowPlane(0, -arm, surface) : display;
            motion(side, PipeMotion.uniform(water, outward.getOrDefault(side, 0d)));
        }
        core = !pump ? PipeContents.EMPTY : pumpFacing.getAxis() == Direction.Axis.Y
            ? belowPlane(-pumpFacing.getStepY() * .25, pumpFacing.getStepY() * .25, surface) : display;
        coreFlows = PipeMotion.uniform(core, pumpFlow).flow();
        rememberMacroGeometry();
    }
    private static PipeContents belowPlane(double from, double to, double surface) {
        double fraction = Math.max(0, Math.min(1, (surface - from) / (to - from)));
        if (to > from) return fraction == 0 ? PipeContents.EMPTY : fraction == 1 ? PipeContents.FULL : PipeContents.of(0, fraction);
        return fraction == 0 ? PipeContents.FULL : fraction == 1 ? PipeContents.EMPTY : PipeContents.of(fraction, 1);
    }
    private void rememberMacroGeometry() { macroCore = core; macroArms.clear(); macroArms.putAll(contents); }
    public void prepareQueue(double capacity, boolean pump, java.util.Set<Direction> sides) {
        Direction front = sides.size() == 2 ? sides.iterator().next() : null;
        if (phases == null) {
            double actual = volume();
            phases = new FluidQueue<>(FluidMaterials::same);
            queueCapacity = Math.max(capacity, actual);
            queueFront = front;
            if (actual > 0 && !fluid.isEmpty()) phases.add(fluid.copyWithAmount(1), actual, false);
            double air = queueCapacity - phases.volume();
            if (air > 0) phases.add(FluidStack.EMPTY, air, false);
            adoptQueue(pump, sides);
        } else if (capacity - queueCapacity > 64 * Math.ulp(Math.max(1, capacity))) {
            // Only newly constructed geometric volume is initialised. Existing
            // air is never recomputed from capacity minus liquid during a tick.
            phases.add(FluidStack.EMPTY, capacity - queueCapacity, false); queueCapacity = capacity;
            adoptQueue(pump, sides);
        } else if (queueCapacity - Math.max(capacity, volume()) > 64 * Math.ulp(Math.max(1, queueCapacity))) {
            // Removed geometry opens its air to the exterior. Real fluid is
            // retained, including an overfull cell until it can drain.
            double release = Math.min(airVolume(), queueCapacity - Math.max(capacity, volume()));
            double left = release; var remaining = new FluidQueue<FluidStack>(FluidMaterials::same);
            for (var part : phases.parts()) {
                double remove = part.identity().isEmpty() ? Math.min(left, part.amount()) : 0;
                left -= remove;
                if (part.amount() > remove) remaining.add(part.identity(), part.amount() - remove, false);
            }
            phases = remaining; queueCapacity -= release; adoptQueue(pump, sides);
        }
        queueFront = front;
    }
    public boolean front(Direction side) { return queueFront == null || side == queueFront; }
    public java.util.List<FluidQueue.Part<FluidStack>> parcels() { return phases == null ? java.util.List.of() : phases.parts(); }
    public double volume(FluidStack identity) {
        return phases == null ? FluidMaterials.same(fluid, identity) ? volume() : 0 : phases.volume(s -> FluidMaterials.same(s, identity));
    }
    public double airVolume() { return phases == null ? 0 : phases.volume(FluidStack::isEmpty); }
    public record Slice(FluidStack fluid, double from, double to) {}
    /** Coordinates run along the positive block axis. Air has no rendered box. */
    public java.util.List<Slice> slices() {
        var result = new java.util.ArrayList<Slice>();
        double at = 0;
        for (var p : parcels()) {
            double end = at + p.amount() / queueCapacity;
            if (!p.identity().isEmpty()) result.add(queueFront != null && queueFront.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? new Slice(p.identity(), 1 - end, 1 - at) : new Slice(p.identity(), at, end));
            at = end;
        }
        return result;
    }
    public void adoptQueue(boolean pump, java.util.Set<Direction> sides) {
        macroLiquid = phases.volume(s -> !s.isEmpty());
        fluid = FluidStack.EMPTY;
        for (var p : phases.parts()) if (!p.identity().isEmpty()) { fluid = p.identity().copyWithAmount(1); break; }
        contents.clear(); intervalFlows.clear();
        double fraction = Math.min(1, macroLiquid / queueCapacity);
        var display = fraction == 0 ? PipeContents.EMPTY : fraction >= 1 ? PipeContents.FULL : PipeContents.of(0, fraction);
        for (var side : sides) motion(side, PipeMotion.uniform(display, outward.getOrDefault(side, 0d)));
        core = pump ? display : PipeContents.EMPTY;
        coreFlows = PipeMotion.uniform(core, pumpFlow).flow(); rememberMacroGeometry();
    }
    public long visualHash() {
        long hash = fluid.isEmpty() ? 0 : 31L * fluid.getFluid().hashCode() + fluid.getComponentsPatch().hashCode();
        for (Direction side : Direction.values()) {
            hash = hash * 31 + content(side).visualHash();
            hash = hash * 31 + Math.round(outward.getOrDefault(side, 0d) * 100);
        }
        if (phases != null) for (var p : phases.parts()) {
            hash = hash * 31 + (p.identity().isEmpty() ? 0 : 31L * p.identity().getFluid().hashCode() + p.identity().getComponentsPatch().hashCode());
            hash = hash * 31 + Math.round(p.amount() * 10000);
        }
        return hash * 31 + core.visualHash();
    }

    /** Actual owned sub-mB portions bridge integer inventory capabilities.
     * Source extraction rounds up into ownership; output delivery rounds down. */
    public static final class Buffer {
        public FluidStack fluid = FluidStack.EMPTY;
        public double amount;
        public boolean delivery;
        private FluidQueue<FluidStack> queue;
        private double remembered;
        private FluidStack rememberedFluid = FluidStack.EMPTY;
        private void prepare() {
            if (queue == null || amount != remembered || !FluidMaterials.same(fluid, rememberedFluid)) {
                queue = new FluidQueue<>(FluidMaterials::same);
                if (amount > 0 && !fluid.isEmpty()) queue.add(fluid.copyWithAmount(1), amount, false);
                remember();
            }
        }
        private void remember() {
            // Match the existing integer-receipt arithmetic boundary. A few
            // ULPs left by a rounded final mB cannot lock the next fluid; real
            // sub-mB stock such as 0.025 mB remains far above this boundary.
            if (FluidBudget.receiptSpace(queue.volume(), 0) == 0) queue = new FluidQueue<>(FluidMaterials::same);
            amount = queue.volume(); var first = queue.peek(true);
            fluid = first == null ? FluidStack.EMPTY : first.identity();
            remembered = amount; rememberedFluid = fluid;
            if (amount == 0) delivery = false;
        }
        public java.util.List<FluidQueue.Part<FluidStack>> parcels() { prepare(); return queue.parts(); }
        public double volume(FluidStack identity) { prepare(); return queue.volume(s -> FluidMaterials.same(s, identity)); }
        /** Integer native APIs settle like identities together. Their genuine
         * sub-mB remainders stay owned while other representable identities pass;
         * the actual pipe's phase queue is never reordered or rounded away. */
        public void settle(FluidStack identity, double wanted) {
            prepare(); double left = wanted; var next = new FluidQueue<FluidStack>(FluidMaterials::same);
            for (var part : queue.parts()) {
                double take = FluidMaterials.same(part.identity(), identity) ? Math.min(left, part.amount()) : 0;
                left -= take;
                if (part.amount() > take) next.add(part.identity(), part.amount() - take, false);
            }
            if (FluidBudget.receiptSpace(left, 0) > 0) throw new IllegalStateException("Native settlement exceeded owned identity stock");
            queue = next; remember();
        }
        public FluidQueue.Part<FluidStack> first() { prepare(); return queue.peek(true); }
        public java.util.List<FluidQueue.Part<FluidStack>> take(double wanted) { prepare(); var result = queue.take(wanted, true); remember(); return result; }
        public boolean matches(FluidStack other) {
            prepare(); return amount == 0 || !fluid.isEmpty() && FluidStack.isSameFluidSameComponents(fluid, other);
        }
        public void add(FluidStack identity, double volume) {
            prepare();
            if (identity.isEmpty()) throw new IllegalArgumentException("External buffers cannot store virtual air");
            if (volume > 0) queue.add(identity.copyWithAmount(1), volume, false);
            else if (volume < 0) {
                var first = queue.peek(true);
                if (first == null || !FluidMaterials.same(first.identity(), identity)
                    || -volume > first.amount() && FluidBudget.receiptSpace(-volume, first.amount()) > 0)
                    throw new IllegalStateException("Conflicting owned pipe withdrawal");
                queue.take(Math.min(first.amount(), -volume), true);
            }
            remember();
        }
    }

    public void write(CompoundTag parent, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ContentsVersion", 1);
        tag.putInt("MotionVersion", 1);
        boolean macro = Double.isFinite(macroLiquid);
        if (macro) {
            tag.putInt("MacroVersion", 2); tag.putDouble("MacroLiquid", volume());
        }
        if (phases != null) {
            tag.putInt("QueueVersion", 2); tag.putDouble("QueueCapacity", queueCapacity);
            tag.putInt("SplitCursor", splitCursor);
            if (queueFront != null) tag.putString("QueueFront", queueFront.getName());
            saveCompactParcels(tag, phases.parts(), registries);
        }
        tag.put("Fluid", fluid.saveOptional(registries));
        tag.put("Core", saveContents(core));
        tag.put("CoreFlows", saveFlows(coreMotion().flow()));
        tag.putDouble("PumpFlow", pumpFlow);
        if (pumpFacing != null) tag.putString("Facing", pumpFacing.getName());
        for (Direction side : Direction.values()) {
            CompoundTag port = new CompoundTag();
            port.putDouble("Flow", outward.getOrDefault(side, 0d));
            port.put("Contents", saveContents(content(side)));
            port.put("IntervalFlows", saveFlows(motion(side).flow()));
            Buffer buffer = buffers.get(side);
            if (buffer != null && buffer.amount > 0) {
                port.putDouble("Owned", buffer.amount); port.put("Fluid", buffer.fluid.saveOptional(registries));
                port.putBoolean("Delivery", buffer.delivery);
                port.put("Parcels", saveParcels(buffer.parcels(), registries));
            }
            tag.put(side.getName(), port);
        }
        parent.put("DCIdealFluid", tag);
    }

    public void read(CompoundTag parent, HolderLookup.Provider registries) {
        previous.clear(); previous.putAll(contents);
        previousOutward.clear(); previousOutward.putAll(outward);
        visualAge = 0;
        outward.clear(); buffers.clear(); contents.clear(); intervalFlows.clear(); core = PipeContents.EMPTY;
        coreFlows = new double[0];
        pumpFlow = 0; pumpFacing = null; fluid = FluidStack.EMPTY;
        macroLiquid = Double.NaN; macroArms.clear(); macroCore = null;
        phases = null; queueCapacity = 0; queueFront = null; splitCursor = 0;
        if (!parent.contains("DCIdealFluid")) return;
        CompoundTag tag = parent.getCompound("DCIdealFluid");
        fluid = FluidStack.parseOptional(registries, tag.getCompound("Fluid"));
        boolean ownedContents = tag.getInt("ContentsVersion") == 1;
        if (ownedContents) { pumpFlow = finite(tag.getDouble("PumpFlow")); core = loadContents(tag.getList("Core", Tag.TAG_DOUBLE)); }
        boolean macro = tag.getInt("MacroVersion") == 2;
        boolean motion = tag.getInt("MotionVersion") == 1;
        if (motion) {
            coreFlows = loadFlows(tag.getList("CoreFlows", Tag.TAG_DOUBLE), core, pumpFlow);
        }
        pumpFacing = Direction.byName(tag.getString("Facing"));
        for (Direction side : Direction.values()) if (tag.contains(side.getName())) {
            CompoundTag port = tag.getCompound(side.getName());
            outward.put(side, ownedContents ? finite(port.getDouble("Flow")) : 0d);
            if (ownedContents) contents.put(side, loadContents(port.getList("Contents", Tag.TAG_DOUBLE)));
            if (motion) {
                intervalFlows.put(side, loadFlows(port.getList("IntervalFlows", Tag.TAG_DOUBLE), content(side), outward.get(side)));
            }
            FluidStack identity = FluidStack.parseOptional(registries, port.getCompound("Fluid"));
            double amount = FluidBudget.receiptSpace(finite(port.getDouble("Owned")), 0);
            if (amount > 0 && !identity.isEmpty()) {
                Buffer buffer = new Buffer(); buffer.fluid = identity; buffer.amount = amount;
                buffer.delivery = port.getBoolean("Delivery"); buffers.put(side, buffer);
                buffer.queue = loadParcels(port.getList("Parcels", Tag.TAG_COMPOUND), registries); buffer.remember();
            }
        }
        if (macro) {
            macroLiquid = FluidBudget.receiptSpace(Math.max(0, finite(tag.getDouble("MacroLiquid"))), 0);
            rememberMacroGeometry();
        }
        if (tag.getInt("QueueVersion") == 2) {
            phases = loadCompactParcels(tag, registries);
            queueFront = Direction.byName(tag.getString("QueueFront"));
            splitCursor = tag.getInt("SplitCursor");
            queueCapacity = Math.max(phases.volume(), tag.getDouble("QueueCapacity"));
            if (!(queueCapacity > 0)) throw new IllegalStateException("Saved pipe has an empty incompressible queue");
            macroLiquid = phases.volume(s -> !s.isEmpty());
        }
    }
    /** Exact lossless palette: repeated fluid/component codecs dominate client
     * update and save cost in a fragmented queue. Air uses index -1. */
    private static void saveCompactParcels(CompoundTag tag, java.util.List<FluidQueue.Part<FluidStack>> parts, HolderLookup.Provider registries) {
        var palette = new java.util.ArrayList<FluidStack>(); var encoded = new ListTag();
        int[] ids = new int[parts.size()]; long[] amounts = new long[parts.size()];
        for(int n=0;n<parts.size();n++) {
            var part=parts.get(n); amounts[n]=Double.doubleToRawLongBits(part.amount()); ids[n]=-1;
            if(part.identity().isEmpty()) continue;
            int id=0; while(id<palette.size() && !FluidMaterials.same(palette.get(id),part.identity())) id++;
            if(id==palette.size()) { palette.add(part.identity()); encoded.add(part.identity().save(registries)); }
            ids[n]=id;
        }
        tag.put("Palette",encoded); tag.putIntArray("ParcelIds",ids); tag.putLongArray("ParcelAmounts",amounts);
    }
    private static FluidQueue<FluidStack> loadCompactParcels(CompoundTag tag, HolderLookup.Provider registries) {
        var encoded=tag.getList("Palette",Tag.TAG_COMPOUND); var palette=new java.util.ArrayList<FluidStack>();
        for(int n=0;n<encoded.size();n++) palette.add(FluidStack.parseOptional(registries,encoded.getCompound(n)));
        int[] ids=tag.getIntArray("ParcelIds"); long[] amounts=tag.getLongArray("ParcelAmounts");
        if(ids.length!=amounts.length) throw new IllegalStateException("Saved pipe queue arrays have different lengths");
        var result=new FluidQueue<FluidStack>(FluidMaterials::same);
        for(int n=0;n<ids.length;n++) {
            if(ids[n]<-1 || ids[n]>=palette.size()) throw new IllegalStateException("Saved pipe queue has an invalid palette index");
            var identity=ids[n]==-1 ? FluidStack.EMPTY : palette.get(ids[n]);
            double amount=Double.longBitsToDouble(amounts[n]);
            if(amount>0 && Double.isFinite(amount) && (ids[n]==-1 || !identity.isEmpty())) result.add(identity,amount,false);
        }
        return result;
    }
    private static ListTag saveParcels(java.util.List<FluidQueue.Part<FluidStack>> parcels, HolderLookup.Provider registries) {
        var result = new ListTag();
        for (var p : parcels) {
            var tag = new CompoundTag(); tag.putDouble("Amount", p.amount());
            tag.put("Fluid", p.identity().save(registries));
            result.add(tag);
        }
        return result;
    }
    private static FluidQueue<FluidStack> loadParcels(ListTag parcels, HolderLookup.Provider registries) {
        var result = new FluidQueue<FluidStack>(FluidMaterials::same);
        for (int i = 0; i < parcels.size(); i++) {
            var tag = parcels.getCompound(i); double amount = tag.getDouble("Amount");
            var identity = FluidStack.parseOptional(registries, tag.getCompound("Fluid"));
            if (amount > 0 && Double.isFinite(amount) && !identity.isEmpty())
                result.add(identity, amount, false);
        }
        return result;
    }
    private static ListTag saveContents(PipeContents contents) {
        ListTag result = new ListTag();
        for (double end : contents.data()) result.add(DoubleTag.valueOf(end));
        return result;
    }
    private static ListTag saveFlows(double[] flows) {
        var tag = new ListTag(); for (double flow : flows) tag.add(DoubleTag.valueOf(flow)); return tag;
    }
    private static double[] loadFlows(ListTag tag, PipeContents contents, double fallback) {
        if (tag.size() != contents.intervals()) return PipeMotion.uniform(contents, fallback).flow();
        double[] flows = new double[tag.size()]; for (int i = 0; i < flows.length; i++) flows[i] = finite(tag.getDouble(i)); return flows;
    }
    private static PipeContents loadContents(ListTag tag) {
        double[] ends = new double[tag.size()];
        for (int i = 0; i < ends.length; i++) ends[i] = tag.getDouble(i);
        return PipeContents.of(ends);
    }
    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }
}

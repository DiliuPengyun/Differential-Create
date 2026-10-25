package com.diliupengyun.differential_create.tests;

import java.util.List;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Replace native generic capabilities while their actual wet endpoint survives. */
final class FluidStructureMachineTests {
    static final List<String> OPERATIONS = List.of("basin_to_spout", "spout_to_basin", "basin_reload", "spout_reload");
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static IFluidHandler cap(NativeFluidTestNetwork n, BlockPos at) {
        return n.h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, n.absolute(at), Direction.UP);
    }
    private static int amount(NativeFluidTestNetwork n, BlockPos at) {
        int value = 0; var handler = cap(n, at);
        for (int i = 0; i < handler.getTanks(); i++) value += handler.getFluidInTank(i).getAmount();
        return value;
    }
    static void change(GameTestHelper h, String operation, Fluid fluid) {
        String name = "structure_machine_" + operation + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var sink = p(16, 2, 4); var pump = p(3, 3, 4);
        n.tank(source, 1, 2); n.machines.put(sink, Direction.UP);
        n.put(sink, operation.startsWith("basin") ? AllBlocks.BASIN.getDefaultState() : AllBlocks.SPOUT.getDefaultState());
        n.route(source, p(0, 3, 4), sink.above(), sink); n.pump(pump, Direction.EAST); n.build(); n.timeout(3998);
        boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, 8000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || amount(n, sink) < 64) return;
            acted[0] = true; int tick = (int)h.getTick(); var old = cap(n, sink); int before = amount(n, sink);
            if (operation.endsWith("reload")) n.reload(sink);
            else {
                var actual = old.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
                n.expect(actual.getAmount() == before, "actual native extraction collects the replaced machine's liquid");
                n.collected.merge(actual.getFluid(), (double)actual.getAmount(), Double::sum); n.kind(actual).output += actual.getAmount();
                n.put(sink, operation.equals("basin_to_spout") ? AllBlocks.SPOUT.getDefaultState() : AllBlocks.BASIN.getDefaultState());
                ((SmartBlockEntity)n.entity(sink)).initialize(); before = 0;
            }
            n.expect(cap(n, sink) != old, "a genuinely new native capability replaces the old one at the same endpoint");
            int checkpoint = before;
            n.after(tick + 1500, () -> {
                n.expect(amount(n, sink) >= checkpoint + 128, "the replacement or reloaded native machine accepts new actual liquid");
                n.done("native_capability_action=" + operation + ", counted_new_stock=" + (amount(n, sink) - checkpoint));
            });
        });
    }
}

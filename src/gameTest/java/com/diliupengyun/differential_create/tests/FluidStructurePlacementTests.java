package com.diliupengyun.differential_create.tests;

import java.util.EnumSet;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Real BlockItem placement and automatic unencased neighbour shape updates. */
final class FluidStructurePlacementTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static void place(NativeFluidTestNetwork n, BlockPos clicked, Direction face) {
        var at = clicked.relative(face); var player = FakePlayerFactory.getMinecraft(n.h.getLevel());
        var stack = new ItemStack(AllBlocks.FLUID_PIPE.asItem()); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var abs = n.absolute(clicked);
        n.force(at);
        var context = new BlockPlaceContext(n.h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
            new BlockHitResult(Vec3.atCenterOf(abs), face, abs, false));
        n.expect(context.getClickedPos().equals(n.absolute(at)), "the native placement targets the requested adjacent cell");
        n.expect(((BlockItem)stack.getItem()).place(context).consumesAction(), "the actual native block item places its pipe");
        if (!n.placed.contains(at)) n.placed.add(at);
        n.expect(n.entity(at) instanceof SmartBlockEntity, "the placed pipe has a real native block entity");
        ((SmartBlockEntity)n.entity(at)).initialize();
        var sides = EnumSet.noneOf(Direction.class);
        for (var direction : Direction.values()) if (n.pipe(at).canHaveFlowToward(n.state(at), direction)) sides.add(direction);
        n.ports.put(at, sides);
    }
    static void automatic(GameTestHelper h, boolean loop, Fluid fluid) {
        String name = "structure_auto_" + (loop ? "loop" : "branch") + "_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var sink = p(16, 0, 4); var pump = p(3, 3, 4); var joint = p(8, 3, 4);
        n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3)); n.pump(pump, Direction.EAST); n.build(); n.timeout(5998);
        boolean[] acted = {false};
        n.after(20, () -> { n.fill(source, fluid, 12000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return; n.audit(); FluidStructureMutationTests.phases(n);
            if (acted[0] || n.amount(sink, fluid) < 64) return;
            acted[0] = true; int tick = (int)h.getTick(), before = n.amount(sink, fluid);
            FluidStructureMutationTests.wrench(n, joint); n.ports.get(joint).add(Direction.NORTH);
            for (int z = 1; z <= 5; z++) place(n, joint.north(z - 1), Direction.NORTH);
            var far = joint.north(5);
            if (loop) {
                var other = joint.east(4); FluidStructureMutationTests.wrench(n, other); n.ports.get(other).add(Direction.NORTH);
                for (int x = 1; x <= 4; x++) place(n, far.east(x - 1), Direction.EAST);
                for (int z = 1; z <= 4; z++) place(n, far.east(4).south(z - 1), Direction.SOUTH);
            } else {
                var receiver = far.below(3); FluidStructureMutationTests.addTank(n, receiver, 1, 1);
                place(n, far, Direction.DOWN); place(n, far.below(), Direction.DOWN);
            }
            // Automatic bends/junctions can grow existing cells. Record the
            // actual geometry without assigning any physical block properties.
            for (var at : n.ports.keySet()) if (n.channel(at)) for (var direction : Direction.values())
                if (n.pipe(at).canHaveFlowToward(n.state(at), direction)) n.ports.get(at).add(direction);
            var cut = joint.north(2);
            n.after(tick + 80, () -> FluidStructureMutationTests.destroyPipe(n, cut));
            n.after(tick + 160, () -> {
                n.put(cut, Blocks.AIR.defaultBlockState()); place(n, cut.south(), Direction.NORTH);
                for (var at : n.ports.keySet()) if (n.channel(at)) for (var direction : Direction.values())
                    if (n.pipe(at).canHaveFlowToward(n.state(at), direction)) n.ports.get(at).add(direction);
            });
            n.after(tick + 1600, () -> {
                n.expect(n.amount(sink, fluid) >= before + 128, "automatic native shape changes preserve and resume the original wet route");
                if (!loop) n.expect(n.amount(far.below(3), fluid) >= 64, "the native item-placed and repaired automatic branch actually delivers");
                n.done("native_block_item_placement=true, automatic_shapes=true, new_loop=" + loop + ", actual_branch_break_and_repair=true");
            });
        });
    }
}

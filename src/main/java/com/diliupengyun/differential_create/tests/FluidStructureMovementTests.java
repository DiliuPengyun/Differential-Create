package com.diliupengyun.differential_create.tests;

import java.util.EnumSet;
import com.diliupengyun.differential_create.kinetics.FluidKinetics;
import com.diliupengyun.differential_create.kinetics.IBearingMotion;
import com.diliupengyun.differential_create.kinetics.PipeHydraulics;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Real Create assembly, actual rotation, and native placement of a wet pipe.
 * Off-world stock is read from the real captured block NBT and counted only for
 * its lifetime aboard the contraption; no fake queue is prefilled or advanced. */
final class FluidStructureMovementTests {
    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    static void bearing(GameTestHelper h, Fluid fluid) {
        String name = "structure_moving_bearing_" + (fluid.isSame(Fluids.WATER) ? "water" : "lava");
        var n = new NativeFluidTestNetwork(h, name); var source = p(0, 8, 4); var sink = p(16, 0, 4); var pump = p(3, 3, 4);
        var target = p(8, 3, 4); var bearingPos = p(8, 3, 2); var drive = bearingPos.north();
        n.tank(source, 1, 4); n.tank(sink, 1, 4); n.route(source, p(0, 3, 4), sink.above(3)); n.pump(pump, Direction.EAST); n.build(); n.timeout(5998);
        n.put(bearingPos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        n.put(bearingPos.south(), Blocks.SLIME_BLOCK.defaultBlockState());
        n.put(drive, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        for (var at : new BlockPos[]{bearingPos, drive}) ((SmartBlockEntity)n.entity(at)).initialize();
        ((CreativeMotorBlockEntity)n.entity(drive)).generatedSpeed.setValue(0);
        int[] stage = {0}, before = {0}, assembledAt = {0}; double[] movingStock = {0}; boolean[] rotated = {false};
        n.after(20, () -> { n.fill(source, fluid, 12000); n.speed(pump, 16); });
        n.watch(() -> {
            if (h.getTick() <= 20) return;
            n.audit(); FluidStructureMutationTests.phases(n);
            var bearing = (MechanicalBearingBlockEntity)n.entity(bearingPos);
            if (stage[0] == 0 && n.amount(sink, fluid) >= 64 && n.volume(target) > 99) {
                stage[0] = 1; assembledAt[0] = (int)h.getTick(); before[0] = n.amount(sink, fluid);
                bearing.assemble(); n.expect(bearing.isRunning() && bearing.getMovedContraption() != null, "the native bearing actually assembled");
                var root = bearing.getMovedContraption();
                n.expect(root.getContraption().getBlocks().size() == 2, "only the slime anchor and its attached wet pipe are moved");
                var info = root.getContraption().getBlocks().values().stream().filter(i -> i.nbt() != null && i.nbt().contains("DCIdealFluid")).findFirst().orElseThrow();
                var saved = new PipeHydraulics(); saved.read(info.nbt(), h.getLevel().registryAccess());
                movingStock[0] = saved.volume(); n.expect(movingStock[0] > 99, "actual captured block NBT owns the moved wet inventory");
                for (var b : saved.buffers.values()) n.expect(b.amount == 0, "the moved mid-line pipe is not a native endpoint stock fixture");
                n.ports.remove(target);
                n.collected.merge(fluid, movingStock[0], Double::sum); n.kind(saved.fluid).output += movingStock[0];
                n.put(target, Blocks.BARRIER.defaultBlockState());
                ((CreativeMotorBlockEntity)n.entity(drive)).generatedSpeed.setValue(16);
                System.out.println("FLUID_STRUCTURE_ASSEMBLY name=" + name + " native_blocks=2 actual_nbt_liquid=" + movingStock[0]);
            }
            if (stage[0] != 1) return;
            double angle = ((IBearingMotion)bearing).differentialcreate$syncedAngle();
            double turn = Math.floorMod((long)Math.floor(angle), 360);
            rotated[0] |= turn > 30 && turn < 330;
            // Wait for actual rotation to bring the native placement within its
            // original cardinal orientation; never assign an angle or speed.
            if (!rotated[0] || h.getTick() < assembledAt[0] + 20 || !(turn < 8 || turn > 352)) return;
            n.put(target, Blocks.AIR.defaultBlockState());
            bearing.disassemble(); n.expect(!bearing.isRunning() && n.pipe(target) != null, "native disassembly returns the wet pipe at its original cardinal position");
            n.ports.put(target, EnumSet.of(Direction.WEST, Direction.EAST));
            n.collected.merge(fluid, -movingStock[0], Double::sum);
            n.kind(new net.neoforged.neoforge.fluids.FluidStack(fluid, 1)).output -= movingStock[0];
            ((SmartBlockEntity)n.entity(target)).initialize();
            ((CreativeMotorBlockEntity)n.entity(drive)).generatedSpeed.setValue(0); stage[0] = 2;
            int tick = (int)h.getTick();
            n.after(tick + 1500, () -> {
                n.expect(rotated[0] && n.amount(sink, fluid) >= before[0] + 128, "after actual moving assembly and placement the wet line makes new native receipts");
                n.done("native_bearing_assembly=true, actual_rotation=true, native_disassembly=true, preserved_nbt_liquid=" + movingStock[0]);
            });
        });
    }
}

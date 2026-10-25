package com.diliupengyun.differential_create.client.ponder;

import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Finite offstage supply/receiver keeps the native hose lesson's visible layout. */
public final class PonderHoseSupply {
    public static final BlockPos PORT = new BlockPos(3, 1, 1);
    public static final int RECEIVER_STOCK = 6000;
    private PonderHoseSupply() {}
    public static void ocean(PonderLevel world) {
        // An initial body of real source blocks, large enough for native hose BFS.
        // Only the original surface selection is drawn; no infinite flag is assigned.
        // Draining searches sideways and upward, never downward from its mouth.
        int side = 101;
        int half = side / 2;
        for (int x = 1 - half; x < 1 - half + side; x++)
            for (int z = 2 - half; z < 2 - half + side; z++)
                for (int y = -2; y <= 0; y++) {
                    var pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).isAir() || !world.getFluidState(pos).isEmpty())
                        world.setBlock(pos, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 2);
                }
    }
    public static void setup(PonderLevel world, boolean filling) {
        // Drive the native stage-edge cog faster; pump speed and throughput
        // still follow the mechanical/fluid solvers in both transfer directions.
        PonderKinetics.of(world).boundary(world, new BlockPos(3, 1, 2), -256);
        // The native template already hides a creative tank under this straight
        // pipe. Replace that unlimited source with finite initial stock, without
        // adding a visible tank or changing the pipe into an elbow.
        world.setBlock(PORT.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        var inventory = new FluidTank(24000);
        inventory.setFluid(new FluidStack(Fluids.WATER, filling ? 24000 : RECEIVER_STOCK));
        PonderSimulation.of(world).fluidBoundary(PORT, Direction.DOWN, inventory);
    }
    public static int stock(PonderLevel world) {
        var boundary = PonderSimulation.of(world).fluidBoundary(PORT, Direction.DOWN);
        return boundary == null ? 0 : boundary.inventory().getFluidAmount();
    }
}

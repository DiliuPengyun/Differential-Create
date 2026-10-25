package com.diliupengyun.differential_create.client.ponder;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Minimum live supply on the original short train, preserving its controls and track. */
public final class PonderNativeTrain {
    private PonderNativeTrain() {}
    public static Selection prepare(net.createmod.ponder.foundation.PonderSceneBuilder builder, Selection selected) {
        var scene = builder.getScene();
        if (!scene.getId().getNamespace().equals("create")) return selected;
        String name = scene.getId().getPath();
        if (!name.equals("train_controls") && !name.equals("train_schedule")) return selected;
        BlockPos bogey = null;
        for (var pos : new BlockPos[]{new BlockPos(3, 2, 4), new BlockPos(4, 2, 4), new BlockPos(4, 2, 7)})
            if (selected.test(pos)) { bogey = pos; break; }
        if (bogey == null) return selected;
        var wheel = bogey;
        int x = name.equals("train_controls") ? 1 : 5, z = bogey.getZ() - 1;
        var origin = new BlockPos(x, 4, z);
        var extra = scene.getSceneBuildingUtil().select().fromTo(x, 3, z, x + 1, 4, z + 2);
        builder.addInstruction(s -> install(s.getWorld(), wheel, origin));
        return selected.copy().add(extra);
    }
    private static void install(PonderLevel world, BlockPos bogey, BlockPos origin) {
        if (!(world.getBlockEntity(bogey) instanceof AbstractBogeyBlockEntity wheel)) return;
        SteamBogey.install(wheel);
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            var pos = origin.offset(x, 0, z);
            world.setBlock(pos.below(), AllBlocks.BLAZE_BURNER.getDefaultState()
                .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.KINDLED), 3);
            world.setBlock(pos, AllBlocks.FLUID_TANK.getDefaultState(), 3);
            var part = (FluidTankBlockEntity) world.getBlockEntity(pos);
            if (pos.equals(origin)) { part.setWidth(2); part.setHeight(1); part.applyFluidTankSize(4); }
            else part.setController(origin);
        }
        var boiler = (FluidTankBlockEntity) world.getBlockEntity(origin);
        boiler.setWindows(false);
        var fuel = origin.offset(0, -1, 2); var water = origin.offset(1, -1, 2);
        world.setBlock(fuel, Blocks.BARREL.defaultBlockState(), 3);
        ((BarrelBlockEntity) world.getBlockEntity(fuel)).setItem(0, new ItemStack(Items.COAL, 64));
        world.setBlock(water, AllBlocks.FLUID_TANK.getDefaultState(), 3);
        ((FluidTankBlockEntity) world.getBlockEntity(water)).getTankInventory().setFluid(new FluidStack(Fluids.WATER, 8000));
        var lever = bogey.above().north();
        world.setBlock(lever, ModContent.GEAR_LEVER.getDefaultState().setValue(GearLeverBlock.GEAR, 2), 3);
        SteamHoseLinks.connect(wheel, boiler, new SteamHosePort(Direction.WEST, new net.minecraft.world.phys.Vec3(0, .5, .5)));
        if (world.scene != null) world.scene.addElement(new PonderHoseElement(bogey));
    }
}

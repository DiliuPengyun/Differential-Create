package com.diliupengyun.differential_create.tests;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;

record Create6WarehouseFixture(PackagerBlockEntity packager, ChestBlockEntity chest, PackagerLinkBlockEntity link) {
    static Create6WarehouseFixture create(GameTestHelper h, BlockPos position, ItemStack stock, UUID frequency) {
        h.setBlock(position.west(), Blocks.CHEST);
        h.setBlock(position, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        h.setBlock(position.above(), AllBlocks.STOCK_LINK.getDefaultState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        PackagerBlockEntity packager = h.getBlockEntity(position);
        PackagerLinkBlockEntity link = h.getBlockEntity(position.above());
        ChestBlockEntity chest = h.getBlockEntity(position.west());
        link.behaviour.freqId = frequency;
        chest.setItem(0, stock.copy());
        packager.initialize(); link.initialize(); packager.targetInventory.findNewCapability();
        h.assertTrue(link.getPackager() == packager, "warehouse stock link resolves its native packager");
        return new Create6WarehouseFixture(packager, chest, link);
    }
}

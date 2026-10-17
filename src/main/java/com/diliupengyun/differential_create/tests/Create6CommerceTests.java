package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.redstoneRequester.AutoRequestData;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlock;
import com.simibubi.create.content.logistics.tableCloth.TableClothBlockEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Player shopping-list and payment behaviour with a real linked warehouse. */
@GameTestHolder("differentialcreate_logistics")
@PrefixGameTestTemplate(false)
public final class Create6CommerceTests {
    private static final BlockPos WAREHOUSE = new BlockPos(3, 3, 4), TICKER = new BlockPos(7, 3, 8), SHOP = new BlockPos(3, 3, 8);
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases = new ArrayList<TestFunction>();
        for (String mode : new String[]{"purchase", "insufficient_payment", "cash_full", "stock_shortage"}) cases.add(test(mode));
        // Correctness probe for an upstream transaction boundary, intentionally opt-in.
        if (Boolean.getBoolean("differentialcreate.logistics.knownIssues")) { cases.add(test("busy_warehouse")); cases.add(test("stale_stock")); }
        return cases;
    }
    private static TestFunction test(String mode) {
        String name = "table_cloth_" + mode;
        return new TestFunction(name, name, "differentialcreate_logistics:kineticphysicstests.physics_test", 120, 0, true, h -> {
            KineticPhysicsTests.forceImpactChunks(h); h.runAfterDelay(8, () -> trade(h, mode));
        });
    }
    private static void trade(GameTestHelper h, String mode) {
        UUID network = UUID.randomUUID();
        var warehouse = Create6WarehouseFixture.create(h, WAREHOUSE, new ItemStack(Items.IRON_INGOT, 17), network);
        h.setBlock(TICKER, AllBlocks.STOCK_TICKER.getDefaultState());
        h.setBlock(TICKER.east(), AllBlocks.BLAZE_BURNER.getDefaultState().setValue(
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
            com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.SMOULDERING));
        StockTickerBlockEntity ticker = h.getBlockEntity(TICKER); ticker.behaviour.freqId = network; ticker.initialize();
        h.setBlock(SHOP, AllBlocks.ANDESITE_TABLE_CLOTH.getDefaultState().setValue(TableClothBlock.HAS_BE, true));
        TableClothBlockEntity shop = h.getBlockEntity(SHOP); shop.initialize(); shop.owner = UUID.randomUUID();
        var order = PackageOrderWithCrafts.simple(List.of(new BigItemStack(new ItemStack(Items.IRON_INGOT), 7)));
        shop.requestData = new AutoRequestData(order, "Shop destination", h.absolutePos(TICKER).subtract(h.absolutePos(SHOP)),
            h.getLevel().dimension().location().toString(), true);
        shop.priceTag.setFilter(new ItemStack(Items.EMERALD)); shop.priceTag.count = 2;
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        h.assertTrue(ticker.isKeeperPresent() && shop.isShop(), "shop has a real nearby keeper and encoded offer");
        shop.useShop(player); shop.useShop(player); shop.useShop(player);
        ItemStack listItem = player.getMainHandItem();
        h.assertTrue(AllItems.SHOPPING_LIST.isIn(listItem) && ShoppingListItem.getList(listItem).getPurchases(h.absolutePos(SHOP)) == 2,
            "shopping list adds purchases and caps at current stock");
        var saved = listItem.save(h.getLevel().registryAccess());
        var restored = ItemStack.parseOptional(h.getLevel().registryAccess(), (net.minecraft.nbt.CompoundTag)saved);
        h.assertTrue(ItemStack.matches(restored, listItem), "shopping-list components survive item serialization");
        player.getInventory().setItem(9, new ItemStack(Items.EMERALD, mode.equals("insufficient_payment") ? 3 : 4));
        if (mode.equals("cash_full")) for (int slot = 0; slot < ticker.getReceivedPaymentsHandler().getSlots(); slot++) ticker.getReceivedPaymentsHandler().insertItem(slot, new ItemStack(Items.DIAMOND, 64), false);
        if (mode.equals("stock_shortage") || mode.equals("stale_stock")) {
            warehouse.chest().setItem(0, new ItemStack(Items.IRON_INGOT, 1));
            if (mode.equals("stock_shortage")) {
                // Fresh-summary denial is distinct from the opt-in stale cache probe.
                com.simibubi.create.content.logistics.packagerLink.LogisticsManager.SUMMARIES.invalidate(network);
                com.simibubi.create.content.logistics.packagerLink.LogisticsManager.ACCURATE_SUMMARIES.invalidate(network);
            }
        }
        if (mode.equals("busy_warehouse")) for (int i = 0; i < 50; i++) warehouse.packager().queuedExitingPackages.add(new BigItemStack(PackageItem.containing(List.of(new ItemStack(Items.STONE))), 1));
        StockTickerInteractionHandler.interactWithLogisticsManagerAt(player, h.getLevel(), h.absolutePos(TICKER));
        boolean success = mode.equals("purchase");
        if (mode.equals("busy_warehouse") || mode.equals("stale_stock")) DifferentialCreate.LOGGER.info("DC_CREATE6_TRADE_PROBE mode={} payments={} hand={} chest={} queue={} packageContents={}", mode, ticker.getReceivedPaymentsHandler().getStackInSlot(0), player.getMainHandItem(), warehouse.chest().getItem(0), warehouse.packager().queuedExitingPackages.size(), warehouse.packager().heldBox.isEmpty() ? "none" : PackageItem.getContents(warehouse.packager().heldBox).getStackInSlot(0));
        if (success) {
            h.assertTrue(player.getMainHandItem().isEmpty() && player.getInventory().getItem(9).isEmpty(), "successful sale consumes the list and exact payment");
            h.assertTrue(ticker.getReceivedPaymentsHandler().getStackInSlot(0).is(Items.EMERALD) && ticker.getReceivedPaymentsHandler().getStackInSlot(0).getCount() == 4,
                "stock keeper receives the payment once");
            h.assertTrue(warehouse.chest().getItem(0).getCount() == 3 && PackageItem.matchAddress(warehouse.packager().heldBox, "Shop destination")
                && PackageItem.getContents(warehouse.packager().heldBox).getStackInSlot(0).getCount() == 14, "sale packages the exact ordered quantity and destination");
        } else {
            h.assertTrue(AllItems.SHOPPING_LIST.isIn(player.getMainHandItem()) && player.getInventory().getItem(9).getCount() == (mode.equals("insufficient_payment") ? 3 : 4),
                "a rejected sale preserves its shopping list and payment: " + mode);
            h.assertTrue(warehouse.packager().heldBox.isEmpty(), "a rejected sale does not create a shipment");
        }
        h.succeed();
    }
}

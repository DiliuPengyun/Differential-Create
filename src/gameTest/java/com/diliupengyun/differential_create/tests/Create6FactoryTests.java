package com.diliupengyun.differential_create.tests;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.repackager.RepackagerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

@net.neoforged.neoforge.gametest.GameTestHolder("differentialcreate_logistics")
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class Create6FactoryTests {
    private static final BlockPos FIRST = new BlockPos(3, 3, 4), SECOND = new BlockPos(11, 3, 4), BOARD = new BlockPos(3, 3, 8);
    @GameTestGenerator public static Collection<TestFunction> cases() {
        return List.of(test("factory_multi_input_recipe", Create6FactoryTests::multi), test("factory_native_crafting", Create6FactoryTests::craft));
    }
    private static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction(name, name, "differentialcreate_logistics:kineticphysicstests.physics_test", 500, 0, true,
            h -> { KineticPhysicsTests.forceImpactChunks(h); h.runAfterDelay(8, () -> body.accept(h)); });
    }
    private static FactoryPanelBlockEntity board(GameTestHelper h, UUID frequency, boolean twoInputs) {
        h.setBlock(BOARD, AllBlocks.FACTORY_GAUGE.getDefaultState());
        FactoryPanelBlockEntity board = h.getBlockEntity(BOARD);
        board.addPanel(PanelSlot.TOP_LEFT, frequency); board.addPanel(PanelSlot.BOTTOM_LEFT, frequency);
        if (twoInputs) board.addPanel(PanelSlot.TOP_RIGHT, frequency);
        board.initialize();
        h.assertTrue(h.getBlockEntity(BOARD) == board, "recipe graph is attached to a live factory gauge");
        return board;
    }
    private static void configure(FactoryPanelBehaviour output, ItemStack result, int quantity, FactoryPanelBehaviour source, int amount) {
        output.setFilter(result); output.setValueSettings(null, new ValueSettings(0, quantity), false);
        output.recipeOutput = quantity; output.recipeAddress = "Factory";
        output.addConnection(source.getPanelPosition()); output.targetedBy.get(source.getPanelPosition()).amount = amount;
    }
    private static void multi(GameTestHelper h) {
        UUID frequency = UUID.randomUUID();
        var gold = Create6WarehouseFixture.create(h, FIRST, new ItemStack(Items.GOLD_INGOT, 16), frequency);
        var dust = Create6WarehouseFixture.create(h, SECOND, new ItemStack(Items.REDSTONE, 8), frequency);
        var board = board(h, frequency, true);
        var inputA = board.panels.get(PanelSlot.TOP_LEFT); inputA.setFilter(new ItemStack(Items.GOLD_INGOT));
        var inputB = board.panels.get(PanelSlot.TOP_RIGHT); inputB.setFilter(new ItemStack(Items.REDSTONE));
        var output = board.panels.get(PanelSlot.BOTTOM_LEFT);
        configure(output, new ItemStack(Items.CLOCK), 1, inputA, 4);
        output.addConnection(inputB.getPanelPosition()); output.targetedBy.get(inputB.getPanelPosition()).amount = 1;
        output.activeCraftingArrangement = List.of(ItemStack.EMPTY, new ItemStack(Items.GOLD_INGOT), ItemStack.EMPTY,
            new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.GOLD_INGOT),
            ItemStack.EMPTY, new ItemStack(Items.GOLD_INGOT), ItemStack.EMPTY);
        for (int i = 0; i < 200 && gold.packager().heldBox.isEmpty(); i++) output.tick();
        h.assertTrue(gold.chest().getItem(0).getCount() == 12 && dust.chest().getItem(0).getCount() == 7,
            "factory recipe requests exact ingredient quantities from two linked inventories");
        h.assertTrue(output.getPromised() == 1, "recipe request records its output promise");
        for (int i = 0; i < 100; i++) output.tick();
        h.assertTrue(gold.chest().getItem(0).getCount() == 12 && dust.chest().getItem(0).getCount() == 7,
            "pending output promise suppresses repeat ingredient extraction");
        BlockPos repackage = new BlockPos(14, 3, 10);
        h.setBlock(repackage.west(), Blocks.CHEST);
        h.setBlock(repackage, AllBlocks.REPACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.EAST));
        RepackagerBlockEntity p = h.getBlockEntity(repackage); p.initialize(); p.targetInventory.findNewCapability();
        ChestBlockEntity chest = h.getBlockEntity(repackage.west());
        chest.setItem(0, gold.packager().heldBox.copy()); gold.packager().heldBox = ItemStack.EMPTY;
        chest.setItem(1, dust.packager().heldBox.copy()); dust.packager().heldBox = ItemStack.EMPTY;
        p.attemptToSend(null);
        h.assertTrue(chest.isEmpty() && p.queuedExitingPackages.size() == 1, "matching multi-link fragments are repackaged together");
        var box = p.queuedExitingPackages.getFirst().stack;
        var context = PackageItem.getOrderContext(box);
        h.assertTrue(PackageItem.matchAddress(box, "Factory") && PackageOrderWithCrafts.hasCraftingInformation(context)
            && context.getCraftingInformation().size() == 9, "repackaging retains destination and shaped recipe arrangement");
        int golden = 0, redstone = 0; var contents = PackageItem.getContents(box);
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            var item = contents.getStackInSlot(slot);
            if (item.is(Items.GOLD_INGOT)) golden += item.getCount();
            if (item.is(Items.REDSTONE)) redstone += item.getCount();
        }
        h.assertTrue(golden == 4 && redstone == 1, "repackaged recipe conserves each ingredient"); h.succeed();
    }
    private static void craft(GameTestHelper h) {
        UUID frequency = UUID.randomUUID();
        var warehouse = Create6WarehouseFixture.create(h, FIRST, new ItemStack(Items.OAK_LOG, 4), frequency);
        var board = board(h, frequency, false);
        var source = board.panels.get(PanelSlot.TOP_LEFT); source.setFilter(new ItemStack(Items.OAK_LOG));
        var output = board.panels.get(PanelSlot.BOTTOM_LEFT);
        configure(output, new ItemStack(Items.OAK_PLANKS), 4, source, 1);
        output.activeCraftingArrangement = List.of(new ItemStack(Items.OAK_LOG));
        for (int i = 0; i < 200 && warehouse.packager().heldBox.isEmpty(); i++) output.tick();
        h.assertTrue(!warehouse.packager().heldBox.isEmpty() && warehouse.chest().getItem(0).getCount() == 3, "factory creates its single-recipe package");
        var box = warehouse.packager().heldBox.copy(); warehouse.packager().heldBox = ItemStack.EMPTY;
        BlockPos crafter = new BlockPos(10, 3, 10), unpacker = crafter.south(), cog = crafter.east(), motor = cog.north();
        h.setBlock(crafter, AllBlocks.MECHANICAL_CRAFTER.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        h.setBlock(crafter.above(), Blocks.CHEST);
        h.setBlock(cog, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        h.setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        ((CreativeMotorBlockEntity)h.getBlockEntity(motor)).generatedSpeed.setValue(128);
        h.setBlock(unpacker, AllBlocks.PACKAGER.getDefaultState().setValue(PackagerBlock.FACING, Direction.SOUTH));
        h.runAfterDelay(12, () -> {
            PackagerBlockEntity p = h.getBlockEntity(unpacker);
            try {
                h.assertTrue(p.unwrapBox(box, true) && p.unwrapBox(box, false), "native package unpacking dispatches its recipe context to the powered crafter");
            } catch (RuntimeException | Error failure) { h.setBlock(motor, Blocks.AIR); h.setBlock(cog, Blocks.AIR); h.setBlock(crafter, Blocks.AIR); throw failure; }
            long start = h.getTick();
            class Poll implements Runnable {
                @Override public void run() {
                    ChestBlockEntity chest = h.getBlockEntity(crafter.above());
                    if (chest.getItem(0).is(Items.OAK_PLANKS) && chest.getItem(0).getCount() == 4) {
                        h.setBlock(motor, Blocks.AIR); h.setBlock(cog, Blocks.AIR); h.setBlock(crafter, Blocks.AIR); h.succeed(); return;
                    }
                    if (h.getTick() - start > 200) {
                        var nativeCrafter = (com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity)h.getBlockEntity(crafter);
                        var diagnostic = "speed=" + nativeCrafter.getSpeed() + " phase=" + com.diliupengyun.differential_create.kinetics.MachinePhase.of(nativeCrafter)
                            + " inventory=" + nativeCrafter.getInventory().getStackInSlot(0) + " target=" + nativeCrafter.getTargetDirection();
                        h.setBlock(motor, Blocks.AIR); h.setBlock(cog, Blocks.AIR); h.setBlock(crafter, Blocks.AIR);
                        h.fail("native powered crafter did not produce the four recipe outputs: " + diagnostic); return;
                    }
                    h.runAfterDelay(1, this::run);
                }
            }
            new Poll().run();
        });
    }
}

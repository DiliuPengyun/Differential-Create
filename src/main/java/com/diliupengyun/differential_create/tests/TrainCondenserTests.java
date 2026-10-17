package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.kinetics.BoilerAdmission;
import com.diliupengyun.differential_create.kinetics.SourcePhysics;
import com.diliupengyun.differential_create.trains.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Per-engine net water accounting through native upgrades, assembly, storage and readback. */
@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainCondenserTests {
    private static final BlockPos WHEEL = TrainTestFixture.WHEEL;
    private TrainCondenserTests() {}

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (boolean large : new boolean[]{false, true}) {
            String size = large ? "large" : "small";
            for (InteractionHand hand : InteractionHand.values())
                for (GameType mode : new GameType[]{GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE})
                    add(tests, "install_" + size + "_" + hand.name().toLowerCase(Locale.ROOT) + "_" + mode.getName(),
                        h -> interaction(h, large, hand, mode));
            add(tests, "drops_" + size, h -> drops(h, large));
            add(tests, "roundtrip_" + size, h -> roundtrip(h, large));
            for (int engines = 1; engines <= 2; engines++) for (int cooled = 0; cooled <= engines; cooled++)
                for (int rpm : new int[]{0, 128, 512}) for (double valve : new double[]{.03, 1}) for (int water : new int[]{0, 1, 4000}) {
                    int count = engines, upgraded = cooled;
                    add(tests, "water_" + size + "_" + engines + "_" + cooled + "_" + rpm + "_" + valve + "_" + water,
                        h -> water(h, large, count, upgraded, rpm, valve, water));
                }
        }
        add(tests, "recipe", h -> {
            var recipe = (CraftingRecipe) h.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("differentialcreate", "condenser_pipe")).orElseThrow().value();
            var grid = new ArrayList<>(List.of(AllItems.COPPER_SHEET.asStack(), AllBlocks.FLUID_PIPE.asStack(), AllItems.COPPER_SHEET.asStack(),
                AllItems.IRON_SHEET.asStack(), new ItemStack(Items.BLUE_ICE), AllItems.IRON_SHEET.asStack(),
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
            var input = CraftingInput.of(3, 3, grid);
            h.assertTrue(recipe.matches(input, h.getLevel()) && recipe.assemble(input, h.getLevel().registryAccess()).is(ModContent.CONDENSER_PIPE.get()),
                "Copper, iron, a fluid pipe and blue ice craft the condenser pipe");
            grid.set(4, ItemStack.EMPTY);
            h.assertTrue(!recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "The condenser cannot be crafted without its blue-ice core");
            grid.set(4, new ItemStack(Items.PACKED_ICE));
            h.assertTrue(!recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "Packed ice cannot replace blue ice");
            h.succeed();
        });
        return tests;
    }
    private static void add(List<TestFunction> tests, String suffix, Consumer<GameTestHelper> run) {
        String name = "train_condenser_" + suffix;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 150, 0, true, run));
    }
    private static PlayerInteractEvent.RightClickBlock click(GameTestHelper h, Player player, InteractionHand hand) {
        var pos = h.absolutePos(WHEEL);
        var event = new PlayerInteractEvent.RightClickBlock(player, hand, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        NeoForge.EVENT_BUS.post(event); return event;
    }
    private static void wheel(GameTestHelper h, boolean large) {
        h.setBlock(WHEEL.below(), AllBlocks.TRACK.getDefaultState());
        h.setBlock(WHEEL, (large ? AllBlocks.LARGE_BOGEY : AllBlocks.SMALL_BOGEY).getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z));
    }
    private static void interaction(GameTestHelper h, boolean large, InteractionHand hand, GameType mode) {
        wheel(h, large);
        h.setBlock(TrainTestFixture.BOILER, AllBlocks.FLUID_TANK.getDefaultState());
        var player = h.makeMockPlayer(mode);
        mode.updatePlayerAbilities(player.getAbilities());
        var pipes = ModContent.CONDENSER_PIPE.asStack(); pipes.setCount(2);
        var engines = AllBlocks.STEAM_ENGINE.asStack(); engines.setCount(2);
        h.runAtTickTime(5, () -> {
            AbstractBogeyBlockEntity bogey = h.getBlockEntity(WHEEL);
            var data = bogey.getBogeyData().copy(); data.putString("OtherMod", "keep"); bogey.setBogeyData(data);
            player.setItemInHand(hand, pipes); click(h, player, hand);
            h.assertTrue(!SteamBogey.condensing(bogey) && pipes.getCount() == 2, "An ordinary bogey rejects a condenser without consuming it");
            player.setItemInHand(hand, engines); var event = click(h, player, hand);
            if (mode == GameType.ADVENTURE) {
                h.assertTrue(!event.isCanceled() && !SteamBogey.powered(bogey) && engines.getCount() == 2, "Adventure mode cannot install an engine");
                SteamBogey.install(bogey); player.setItemInHand(hand, pipes); click(h, player, hand);
                h.assertTrue(!SteamBogey.condensing(bogey) && pipes.getCount() == 2, "Adventure mode cannot install a condenser");
                h.succeed(); return;
            }
            int remaining = mode == GameType.CREATIVE ? 2 : 1;
            h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction() && SteamBogey.powered(bogey) && engines.getCount() == remaining,
                "Engine installation consumes one survival item and preserves the bogey");
            click(h, player, hand);
            h.assertTrue(engines.getCount() == remaining, "Repeated engine installation cannot consume extra items");
            SteamHoseLinks.connect(bogey, h.getBlockEntity(TrainTestFixture.BOILER));
            var tank = SteamHoseLinks.tank(bogey);
            player.setItemInHand(hand, pipes); event = click(h, player, hand);
            h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction() && SteamBogey.condensing(bogey) && pipes.getCount() == remaining,
                "Condenser installation consumes exactly one survival item");
            h.assertTrue(tank != null && SteamHoseLinks.tank(bogey) == tank, "Installing the condenser preserves an existing boiler connection");
            click(h, player, hand);
            h.assertTrue(pipes.getCount() == remaining && bogey.getBogeyData().getString("OtherMod").equals("keep"), "Duplicate upgrades preserve stock and unrelated bogey data");
            var saved = bogey.saveWithFullMetadata(h.getLevel().registryAccess());
            bogey.loadWithComponents(saved, h.getLevel().registryAccess()); SteamHoseLinks.unlink(bogey);
            h.assertTrue(SteamBogey.condensing(bogey), "Block readback and hose disconnection retain the condenser");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.setShiftKeyDown(true); click(h, player, hand);
            h.assertTrue(!SteamBogey.powered(bogey) && !bogey.getBogeyData().contains(SteamBogey.CONDENSER)
                && player.getInventory().countItem(AllBlocks.STEAM_ENGINE.asItem()) == 1
                && player.getInventory().countItem(ModContent.CONDENSER_PIPE.get()) == 1, "Removing the engine refunds both upgrades once and clears condenser state");
            SteamBogey.install(bogey);
            h.assertTrue(!SteamBogey.condensing(bogey), "A newly installed replacement engine cannot inherit a free condenser");
            h.succeed();
        });
    }
    private static void drops(GameTestHelper h, boolean large) {
        wheel(h, large);
        h.runAtTickTime(5, () -> {
            SteamBogey.install(h.getBlockEntity(WHEEL)); SteamBogey.installCondenser(h.getBlockEntity(WHEEL));
            var pos = h.absolutePos(WHEEL); h.getLevel().destroyBlock(pos, true);
            var items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2));
            h.assertTrue(items.stream().filter(e -> e.getItem().is(ModContent.CONDENSER_PIPE.get())).mapToInt(e -> e.getItem().getCount()).sum() == 1
                && items.stream().filter(e -> AllBlocks.STEAM_ENGINE.isIn(e.getItem())).mapToInt(e -> e.getItem().getCount()).sum() == 1,
                "Breaking the bogey returns one engine and one condenser");
            h.succeed();
        });
    }
    private static void setup(GameTestHelper h, int engines, int cooled) {
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
            for (int y = 2; y < 4; y++) h.setBlock(TrainTestFixture.BOILER.offset(x, y, z), AllBlocks.FLUID_TANK.getDefaultState());
            var pos = TrainTestFixture.BOILER.offset(x, -1, z);
            h.setBlock(pos, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, HeatLevel.KINDLED));
            ((BlazeBurnerBlockEntity) h.getBlockEntity(pos)).isCreative = true;
        }
        if (engines == 2) SteamBogey.install(h.getBlockEntity(WHEEL.south(6)));
        if (cooled > 0) SteamBogey.installCondenser(h.getBlockEntity(WHEEL));
        if (cooled > 1) SteamBogey.installCondenser(h.getBlockEntity(WHEEL.south(6)));
    }
    private static void water(GameTestHelper h, boolean large, int engines, int cooled, int rpm, double valve, int water) {
        TrainTestFixture.create(h, "flat", large, false, engines == 2, setup -> setup(setup, engines, cooled), f -> {
            var steam = TrainPhysics.carriage(f.train.carriages.getFirst());
            h.assertTrue(steam.layout.motors.size() == engines && steam.layout.motors.stream().filter(TrainAssembly.Motor::condenser).count() == cooled,
                "Native assembly captures each bogey's own condenser");
            var fluids = f.train.carriages.getFirst().storage.getFluids(); fluids.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            var reserve = fluids.storages.entrySet().stream().filter(e -> !steam.layout.boilers.containsKey(e.getKey())).findFirst().orElseThrow().getValue();
            reserve.fill(new FluidStack(Fluids.WATER, water), FluidAction.EXECUTE);
            TrainPhysics.shift(f.train, 0); f.train.throttle = valve;
            steam.engines.values().forEach(e -> e.omega = rpm * Math.PI / 30);
            double flow = 10 * valve * Math.min(1, Math.max(16, rpm) / 256d) * Math.clamp((512 - rpm) / 64d, 0, 1);
            double demand = flow * (engines - .9 * cooled), delivered = Math.min(water, demand);
            f.tick(1, false);
            var boiler = steam.boilers.values().iterator().next();
            double paid = water - f.water() - steam.water.values().stream().mapToDouble(Double::doubleValue).sum();
            h.assertTrue(Math.abs(paid - delivered) < 1e-8 && Math.abs(BoilerAdmission.demand(boiler) - demand) < 1e-8,
                "Shared boiler pays 100% per normal engine and 10% per upgraded engine, including fractional water and shortages");
            double fraction = demand > 0 ? delivered / demand : 0;
            for (var motor : steam.layout.motors) {
                double rated = SourcePhysics.steamCurve(motor.capacity(), 1, 1, 1).powerLimit() * TrainMechanics.TORQUE_SCALE * Math.PI / 30;
                h.assertTrue(Math.abs(steam.engines.get(motor.pos()).power - rated * valve * fraction) < 1e-7,
                    "Condensation changes net water use without reducing rated power or duplicating shared steam");
            }
            h.assertTrue(f.train.speed == 0 && f.water() >= 0, "Neutral and empty storage cannot gain free propulsion or negative water");
            f.finish();
        });
    }
    private static void roundtrip(GameTestHelper h, boolean large) {
        TrainTestFixture.create(h, "flat", large, false, false, setup -> setup(setup, 1, 1), f -> {
            TrainPhysics.shift(f.train, 0);
            for (int tick = 0; tick < 20; tick++) f.tick(1, false);
            var before = TrainPhysics.carriage(f.train.carriages.getFirst());
            int water = f.water(); double carry = before.water.values().stream().mapToDouble(Double::doubleValue).sum();
            f.reload(); var after = TrainPhysics.carriage(f.train.carriages.getFirst());
            h.assertTrue(after.layout.motors.getFirst().condenser() && f.water() == water
                && after.water.values().stream().mapToDouble(Double::doubleValue).sum() == carry,
                "Native train reload retains the upgrade and paid fractional water without refunding water");
            h.assertTrue(f.train.carriages.getFirst().leadingBogey().bogeyData.getBoolean(SteamBogey.CONDENSER), "Native moving bogey retains its condenser flag");
            var wheel = BlockPos.containing(f.train.carriages.getFirst().leadingBogey().getAnchorPosition());
            f.train.speed = 0;
            h.assertTrue(f.train.disassemble(Direction.SOUTH, wheel.north()), "Condensing train disassembles through the native station path");
            h.runAfterDelay(20, () -> {
                h.assertTrue(SteamBogey.condensing(h.getLevel().getBlockEntity(wheel)), "Final native disassembly restores the installed condenser");
                f.finish();
            });
        });
    }
}

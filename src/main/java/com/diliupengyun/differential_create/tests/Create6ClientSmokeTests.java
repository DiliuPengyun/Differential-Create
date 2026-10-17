package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.ChainConveyorTravel;
import com.diliupengyun.differential_create.kinetics.IChainPackageMotion;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRidingHandler;
import com.simibubi.create.content.logistics.box.PackageItem;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Actual client class transformation, BE packets, native riding and Ponder ticks. */
public final class Create6ClientSmokeTests {
    private static final BlockPos POS = new BlockPos(0, -59, 3), OTHER = POS.south(8);
    private static boolean requested, done;
    private static int frames;
    private static long deadline;
    private static CompletableFuture<?> fixture;
    private Create6ClientSmokeTests() {}
    public static void frame(RenderFrameEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        try {
            if (!requested) {
                if (mc.getOverlay() != null || mc.screen == null || ++frames < 30) return;
                requested = true; deadline = System.nanoTime() + 180_000_000_000L;
                mc.options.pauseOnLostFocus = false; mc.options.renderDistance().set(2); mc.options.simulationDistance().set(5);
                var rules = new GameRules(); rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                var settings = new LevelSettings("Create logistics smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("create6_logistics", settings, new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the logistics client fixture");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            if (fixture == null) {
                mc.setScreen(null);
                fixture = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    level.setBlockAndUpdate(POS, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
                    level.setBlockAndUpdate(OTHER, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
                    var a = (ChainConveyorBlockEntity)level.getBlockEntity(POS);
                    var b = (ChainConveyorBlockEntity)level.getBlockEntity(OTHER);
                    a.prepareStats(); b.prepareStats(); a.addConnectionTo(OTHER); b.addConnectionTo(POS);
                    var box = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, 17))); PackageItem.addAddress(box, "Client");
                    a.addTravellingPackage(new ChainConveyorPackage(2.5f, box), OTHER.subtract(POS));
                    a.notifyUpdate(); b.notifyUpdate();
                    level.getServer().getPlayerList().getPlayers().getFirst().teleportTo(.5, -57, 3.5);
                    level.tickRateManager().setFrozen(true);
                    return null;
                }); return;
            }
            if (!fixture.isDone()) return; fixture.join();
            if (!(mc.level.getBlockEntity(POS) instanceof ChainConveyorBlockEntity a)
                || !(mc.level.getBlockEntity(OTHER) instanceof ChainConveyorBlockEntity)) return;
            var offset = OTHER.subtract(POS);
            if (!a.connections.contains(offset) || a.getTravellingPackages().getOrDefault(offset, List.of()).isEmpty()) return;
            if (com.simibubi.create.foundation.utility.ServerSpeedProvider.get() < .99f) return;
            var received = a.getTravellingPackages().get(offset).getFirst();
            if (!((IChainPackageMotion)received).differentialcreate$motion().directionKnown
                || !PackageItem.matchAddress(received.item, "Client") || PackageItem.getContents(received.item).getStackInSlot(0).getCount() != 17)
                throw new AssertionError("Native BE packet lost chain direction or parcel components");
            // Keep the server fixture frozen. The native client getSpeed() is
            // zero while frozen, so use an unfrozen, detached local speed input.
            mc.level.tickRateManager().setFrozen(false);
            a.network = null;
            mc.player.setYRot(0); mc.player.setXRot(0);
            var update = ChainConveyorRidingHandler.class.getDeclaredMethod("updateTargetPosition", Minecraft.class, ChainConveyorBlockEntity.class);
            update.setAccessible(true);
            int checks = 0;
            try {
                for (int sign : new int[]{-1, 1}) for (float rpm : new float[]{256, 1024, (float)(540 * Math.PI), 2048, 8192}) {
                    a.setSpeed(sign * rpm); a.prepareStats();
                    float tangent = a.connectionStats.get(offset).tangentAngle();
                    ChainConveyorRidingHandler.ridingChainConveyor = POS;
                    ChainConveyorRidingHandler.ridingConnection = null;
                    ChainConveyorRidingHandler.flipped = sign < 0;
                    ChainConveyorRidingHandler.chainPosition = a.wrapAngle(tangent - sign);
                    update.invoke(null, mc, a);
                    if (!offset.equals(ChainConveyorRidingHandler.ridingConnection))
                        throw new AssertionError("Native client riding missed its branch at " + sign * rpm + "; actual=" + a.getSpeed()
                            + "; timeScale=" + com.simibubi.create.foundation.utility.ServerSpeedProvider.get());
                    checks++;
                }
            } finally {
                ChainConveyorRidingHandler.ridingChainConveyor = null;
                ChainConveyorRidingHandler.ridingConnection = null;
            }
            var virtual = new ChainConveyorBlockEntity(a.getType(), POS.east(32), a.getBlockState());
            virtual.setLevel(mc.level); virtual.markVirtual(); virtual.setSpeed(128);
            var box = new ChainConveyorPackage(0, received.item.copy()); virtual.addLoopingPackage(box);
            virtual.tick(); virtual.tick();
            if (Math.abs(box.chainPosition - 2 * ChainConveyorTravel.degrees(128)) > .0001)
                throw new AssertionError("Ponder conveyor must keep advancing when its virtual world time is fixed");
            if (mc.getBlockEntityRenderDispatcher().getRenderer(a) == null) throw new AssertionError("Native conveyor renderer is unavailable");
            DifferentialCreate.LOGGER.info("DC_CREATE6_CLIENT_PASS: native packet retained parcel/direction; {} signed riding branch cases; Ponder repeated tick and renderer checks", checks);
            done = true; mc.stop();
        } catch (Throwable failure) {
            DifferentialCreate.LOGGER.error("DC_CREATE6_CLIENT_FAIL", failure); done = true; mc.stop();
        }
    }
}

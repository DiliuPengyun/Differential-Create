package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.SteamHoseGeometry;
import com.diliupengyun.differential_create.client.SteamHosePreview;
import com.diliupengyun.differential_create.client.SteamHoseRenderer;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.diliupengyun.differential_create.trains.SteamHosePort;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native click packets must agree with the live preview and the installed hose's endpoint. */
public final class TrainClientHosePortTests {
    private static final BlockPos TANK = new BlockPos(5, -58, -36), WHEEL = TANK.east(4);
    private static final int SCENARIOS = 24;
    private static int scenario, stage, age;
    private static boolean initialized;
    private static CompletableFuture<?> setup;
    private static CompletableFuture<Boolean> verification;
    private static SteamHoseGeometry.Connection preview;
    private TrainClientHosePortTests() {}

    public static boolean tick(Minecraft mc) throws Exception {
        if (scenario == SCENARIOS) return true;
        if (++age > 1200) throw new AssertionError("Client centred hose point timed out: " + scenario + " stage " + stage);
        mc.player.setYRot(0); mc.player.setXRot(25);
        switch (stage) {
            case 0 -> {
                setup = mc.getSingleplayerServer().submit(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    if (!initialized) {
                        level.setBlockAndUpdate(TANK, AllBlocks.FLUID_TANK.getDefaultState());
                        level.setBlockAndUpdate(TANK.above(), AllBlocks.FLUID_TANK.getDefaultState());
                        level.setBlockAndUpdate(WHEEL.below(), AllBlocks.TRACK.getDefaultState());
                        level.setBlockAndUpdate(WHEEL, AllBlocks.LARGE_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS, Direction.Axis.Z));
                        SteamBogey.install((AbstractBogeyBlockEntity) level.getBlockEntity(WHEEL)); initialized = true;
                    }
                    SteamHoseLinks.unlink(level.getBlockEntity(WHEEL));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    var hose = ModContent.STEAM_HOSE.asStack(); var tag = new CompoundTag();
                    tag.putInt("PointScenario", scenario); tag.putString("OtherMod", "keep");
                    hose.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); player.setItemInHand(hand(), hose);
                    player.teleportTo(7.5, -57.2, -39.5); player.getAbilities().flying = true;
                    player.onUpdateAbilities(); player.inventoryMenu.broadcastChanges(); return null;
                });
                advance(1);
            }
            case 1 -> {
                if (!setup.isDone()) return false;
                setup.join();
                var held = mc.player.getItemInHand(hand());
                if (!(held.getItem() instanceof SteamHoseItem) || data(held).getInt("PointScenario") != scenario
                    || mc.level.getBlockEntity(member()) == null || mc.level.getBlockEntity(WHEEL) == null
                    || mc.player.position().distanceToSqr(new Vec3(7.5, -57.2, -39.5)) > 1) return false;
                if (!click(mc, engineFirst() ? engineHit() : tankHit()).consumesAction())
                    throw new AssertionError("First endpoint click was not consumed");
                advance(2);
            }
            case 2 -> {
                var held = mc.player.getItemInHand(hand());
                if (!SteamHoseItem.hasSelection(held) || !serverMatches(mc, true)) return false;
                if (!engineFirst()) checkPort(SteamHoseItem.selectedTankPort(held));
                mc.hitResult = engineFirst() ? tankHit() : engineHit(); SteamHosePreview.tick();
                preview = SteamHosePreview.connection();
                if (!SteamHosePreview.valid() || preview == null) throw new AssertionError("Face centre has no valid live preview");
                close(preview.tank().point(), port().position(member()).add(port().normal().scale(.015)));
                close(preview.tank().normal(), port().normal());
                close(preview.engine().point(), Vec3.atCenterOf(WHEEL).add(0, .515, 0));
                close(preview.engine().normal(), new Vec3(0, 1, 0));
                if (!click(mc, (BlockHitResult) mc.hitResult).consumesAction()) throw new AssertionError("Second endpoint click was not consumed");
                advance(3);
            }
            case 3 -> {
                if (SteamHoseItem.hasSelection(mc.player.getItemInHand(hand())) || !serverMatches(mc, false)) return false;
                var engine = mc.level.getBlockEntity(WHEEL); var link = (SteamHoseLinks.EngineLink) engine;
                if (link.differentialcreate$port() == null) return false;
                checkPort(link.differentialcreate$port());
                if (!member().subtract(WHEEL).equals(link.differentialcreate$hose())) throw new AssertionError("Hose was moved to the multiblock controller");
                var tank = (FluidTankBlockEntity) mc.level.getBlockEntity(member());
                var installed = SteamHoseGeometry.connect(engine.getBlockState(), Vec3.atCenterOf(WHEEL),
                    SteamHoseRenderer.bounds(tank), Vec3.atCenterOf(member()), link.differentialcreate$port());
                close(installed.tank().point(), preview.tank().point()); close(installed.tank().normal(), preview.tank().normal());
                close(installed.engine().point(), preview.engine().point()); close(installed.engine().normal(), preview.engine().normal());
                SteamHosePreview.tick();
                if (SteamHosePreview.active() || SteamHosePreview.connection() != null) throw new AssertionError("Completed connection retained its preview");
                if (scenario == 1 || scenario == 2) {
                    if (age < 20) return false;
                    Path path = Path.of("../../..", "train-interaction", "hose-click-" + face().getName() + ".png").toAbsolutePath().normalize();
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(path); }
                }
                scenario++; advance(0);
                if (scenario == SCENARIOS) DifferentialCreate.LOGGER.info("DC_TRAIN_CLIENT_PORTS_PASS: {} six-face/order/hand preview and native packet scenarios", scenario);
            }
            default -> throw new AssertionError("Unknown port stage");
        }
        return false;
    }

    private static Direction face() { return Direction.values()[scenario % 6]; }
    private static BlockPos member() { return face() == Direction.DOWN ? TANK : TANK.above(); }
    private static boolean engineFirst() { return scenario >= 12; }
    private static InteractionHand hand() { return scenario / 6 % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND; }
    private static SteamHosePort port() {
        var face = face();
        return new SteamHosePort(face, new Vec3(.5 + .5 * face.getStepX(), .5 + .5 * face.getStepY(), .5 + .5 * face.getStepZ()));
    }
    private static BlockHitResult tankHit() {
        var face = face();
        var point = switch (face.getAxis()) {
            case X -> new Vec3(face.getStepX() > 0 ? 1 : 0, .6875, .8125);
            case Y -> new Vec3(.25, face.getStepY() > 0 ? 1 : 0, .8125);
            case Z -> new Vec3(.25, .6875, face.getStepZ() > 0 ? 1 : 0);
        };
        return new BlockHitResult(Vec3.atLowerCornerOf(member()).add(point), face, member(), false);
    }
    private static BlockHitResult engineHit() { return new BlockHitResult(Vec3.atCenterOf(WHEEL), Direction.NORTH, WHEEL, false); }
    private static CompoundTag data(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    private static InteractionResult click(Minecraft mc, BlockHitResult hit) {
        mc.hitResult = hit;
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction() || result == InteractionResult.FAIL) return result;
        return mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
    }
    private static boolean serverMatches(Minecraft mc, boolean selected) {
        if (verification == null) {
            verification = mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst(); var held = player.getItemInHand(hand());
                if (SteamHoseItem.hasSelection(held) != selected) return false;
                if (held.getCount() != 1 || !data(held).getString("OtherMod").equals("keep")) throw new AssertionError("Point selection changed the hose or unrelated item data");
                if (selected) { if (!engineFirst()) checkPort(SteamHoseItem.selectedTankPort(held)); }
                else {
                    var link = (SteamHoseLinks.EngineLink) player.level().getBlockEntity(WHEEL); checkPort(link.differentialcreate$port());
                    if (data(held).contains(SteamHosePort.KEY)) throw new AssertionError("Finished selection retained the pending point");
                }
                return true;
            });
            return false;
        }
        if (!verification.isDone()) return false;
        boolean result = verification.join(); verification = null; return result;
    }
    private static void checkPort(SteamHosePort actual) {
        if (actual == null || actual.face() != face()) throw new AssertionError("Native packet lost the selected face");
        close(actual.point(), port().point());
    }
    private static void close(Vec3 actual, Vec3 expected) {
        if (actual.distanceToSqr(expected) > 1e-12) throw new AssertionError("Centred hose point differs: " + actual + " / " + expected);
    }
    private static void advance(int next) { stage = next; age = 0; verification = null; }
}

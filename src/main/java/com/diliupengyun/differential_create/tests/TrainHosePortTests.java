package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.client.SteamHoseGeometry;
import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.trains.SteamBogey;
import com.diliupengyun.differential_create.trains.SteamHoseItem;
import com.diliupengyun.differential_create.trains.SteamHoseLinks;
import com.diliupengyun.differential_create.trains.SteamHosePort;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("differentialcreate_trains")
@PrefixGameTestTemplate(false)
public final class TrainHosePortTests {
    private static final BlockPos TANK = new BlockPos(2, 4, 4), WHEEL = new BlockPos(6, 4, 5), NATIVE = new BlockPos(5, 4, 4);

    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (Direction face : Direction.values()) for (String kind : new String[]{"small", "large", "native"}) {
            for (boolean reverse : new boolean[]{false, true}) for (InteractionHand hand : InteractionHand.values())
                add(tests, "click_" + kind + "_" + face.getName() + "_" + reverse + "_" + hand.name().toLowerCase(Locale.ROOT),
                    h -> click(h, kind, face, reverse, hand));
            for (Rotation rotation : Rotation.values())
                add(tests, "rebuild_" + kind + "_" + face.getName() + "_" + rotation.name().toLowerCase(Locale.ROOT),
                    h -> rebuild(h, kind, face, rotation));
            if (!kind.equals("native")) add(tests, "moving_" + kind + "_" + face.getName(), h -> moving(h, kind.equals("large"), face));
        }
        add(tests, "transform_and_validation", TrainHosePortTests::transforms);
        return tests;
    }

    private static void add(ArrayList<TestFunction> tests, String suffix, java.util.function.Consumer<GameTestHelper> run) {
        String name = "train_hose_port_" + suffix;
        if (name.matches(System.getProperty("differentialcreate.trains.select", ".*")))
            tests.add(new TestFunction(name, name, "differentialcreate_trains:kineticphysicstests.physics_test", 130, 0, true, run));
    }

    private static BlockPos structure(GameTestHelper h, String kind) {
        TrainIntegrationTests.structure(h, 0, false);
        if (kind.equals("large")) {
            h.setBlock(WHEEL, AllBlocks.LARGE_BOGEY.getDefaultState().setValue(AbstractBogeyBlock.AXIS,Direction.Axis.Z)); SteamBogey.install(h.getBlockEntity(WHEEL));
        }
        if (kind.equals("native")) { h.setBlock(NATIVE.below(), Blocks.STONE); h.setBlock(NATIVE, AllBlocks.STEAM_ENGINE.getDefaultState()); }
        return kind.equals("native") ? NATIVE : WHEEL;
    }

    private static SteamHosePort port(Direction face, boolean edge) {
        return SteamHosePort.fromHit(BlockPos.ZERO, face, edge ? new Vec3(.99, .01, .98) : new Vec3(.23, .67, .81));
    }

    private static void input(GameTestHelper h, Player player, InteractionHand hand, BlockPos local, SteamHosePort port) {
        var pos = h.absolutePos(local);
        var hit = port == null ? new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
            : new BlockHitResult(port.position(pos), port.face(), pos, false);
        var event = new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit);
        NeoForge.EVENT_BUS.post(event);
        h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction(), "Native hose event consumes endpoint input");
    }

    private static void click(GameTestHelper h, String kind, Direction face, boolean reverse, InteractionHand hand) {
        var enginePos = structure(h, kind);
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(h.absolutePos(enginePos)));
        var stack = ModContent.STEAM_HOSE.asStack(); var itemData = new CompoundTag(); itemData.putString("OtherMod", "keep");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(itemData)); player.setItemInHand(hand, stack);
        h.runAtTickTime(25, () -> {
            var engine = h.getBlockEntity(enginePos); var link = (SteamHoseLinks.EngineLink) engine;
            for (boolean edge : new boolean[]{false, true}) {
                var selected = port(face, edge);
                input(h, player, hand, reverse ? enginePos : TANK, reverse ? null : selected);
                if (!reverse) closePort(h, SteamHoseItem.selectedTankPort(stack), selected, "First boiler click records precise face and point");
                // Item component serialization must retain a pending boiler port.
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(stack.get(DataComponents.CUSTOM_DATA).copyTag()));
                input(h, player, hand, reverse ? TANK : enginePos, reverse ? selected : null);
                closePort(h, link.differentialcreate$port(), selected, "Both endpoint orders preserve exact click point and face");
                h.assertTrue(SteamHoseLinks.tank(engine) != null && !SteamHoseItem.hasSelection(stack), "Connection activates the boiler and finishes selection");
                h.assertTrue(!stack.get(DataComponents.CUSTOM_DATA).contains(SteamHosePort.KEY), "Completed selection removes its port component");
                var geometry = SteamHoseGeometry.connect(engine.getBlockState(), Vec3.atCenterOf(engine.getBlockPos()),
                    new AABB(h.absolutePos(TANK)), Vec3.atCenterOf(h.absolutePos(TANK)), selected);
                close(h, geometry.tank().point(), selected.position(h.absolutePos(TANK)).add(selected.normal().scale(.015)), "Hose ends at the clicked surface, including edge clicks");
                close(h, geometry.tank().normal(), selected.normal(), "Hose leaves the selected face, including top and bottom");
                var saved = engine.saveWithFullMetadata(h.getLevel().registryAccess());
                SteamHoseLinks.unlink(engine);
                h.assertTrue(link.differentialcreate$port() == null, "Unlink removes precise port data");
                engine.loadWithComponents(saved, h.getLevel().registryAccess()); SteamHoseLinks.ensureRegistered(engine);
                closePort(h, link.differentialcreate$port(), selected, "Native block entity save and load preserves the exact port");
                input(h, player, hand, reverse ? enginePos : TANK, reverse ? null : port(face, !edge));
                player.setShiftKeyDown(true); input(h, player, hand, WHEEL, null); player.setShiftKeyDown(false);
                h.assertTrue(!SteamHoseItem.hasSelection(stack) && !stack.get(DataComponents.CUSTOM_DATA).contains(SteamHosePort.KEY)
                    && stack.get(DataComponents.CUSTOM_DATA).copyTag().getString("OtherMod").equals("keep"), "Cancellation clears the pending port and retains unrelated data");
                closePort(h, link.differentialcreate$port(), selected, "Cancel does not change the existing physical connection");
            }
            SteamHoseLinks.connect(engine, h.getBlockEntity(TANK));
            closePort(h, link.differentialcreate$port(), new SteamHosePort(Direction.UP, new Vec3(.5, 1, .5)),
                "Programmatic connection saves an explicit top-centre endpoint");
            h.succeed();
        });
    }

    private static void rebuild(GameTestHelper h, String kind, Direction face, Rotation rotation) {
        var enginePos = structure(h, kind);
        for (int x = 2; x <= 10; x++) for (int z = 4; z <= 9; z++) {
            var at=new BlockPos(x,3,z);if(h.getBlockState(at).isAir())h.setBlock(at,Blocks.STONE);
        }
        h.getLevel().addFreshEntity(new SuperGlueEntity(h.getLevel(), SuperGlueEntity.span(h.absolutePos(new BlockPos(2, 3, 4)), h.absolutePos(new BlockPos(10, 7, 9)))));
        h.runAtTickTime(25, () -> {
            var selected = port(face, false);
            SteamHoseLinks.connect(h.getBlockEntity(enginePos), h.getBlockEntity(TANK), selected);
            var cc = new CarriageContraption(Direction.SOUTH);
            try { h.assertTrue(cc.assemble(h.getLevel(), h.absolutePos(WHEEL)), "Precise hose assembles in a native carriage"); }
            catch (com.simibubi.create.content.contraptions.AssemblyException e) { throw new IllegalStateException(e); }
            var localEngine = h.absolutePos(enginePos).subtract(cc.anchor);
            var localTank = h.absolutePos(TANK).subtract(cc.anchor);
            var transform = new StructureTransform(cc.anchor, Direction.Axis.Y, rotation, Mirror.NONE);
            var expectedPoint = transform.apply(selected.position(localTank));
            var expectedNormal = transform.applyWithoutOffsetUncentered(selected.normal());
            cc.removeBlocksFromWorld(h.getLevel(), BlockPos.ZERO); CarriageContraptionEntity.create(h.getLevel(), cc);
            cc.addBlocksToWorld(h.getLevel(), transform);
            h.runAfterDelay(20, () -> {
                var engine = h.getLevel().getBlockEntity(transform.apply(localEngine));
                var link = (SteamHoseLinks.EngineLink) engine; var movedPort = link.differentialcreate$port();
                h.assertTrue(movedPort != null && SteamHoseLinks.tank(engine) != null, "Native rotated rebuild restores a working precise hose");
                close(h, movedPort.position(engine.getBlockPos().offset(link.differentialcreate$hose())), expectedPoint, "Rotation preserves the point on the same material surface");
                close(h, movedPort.normal(), expectedNormal, "Rotation restores the correct outward connection face");
                h.succeed();
            });
        });
    }

    private static void transforms(GameTestHelper h) {
        for (Direction face : Direction.values()) for (Direction.Axis axis : Direction.Axis.values())
            for (Rotation rotation : Rotation.values()) for (Mirror mirror : Mirror.values()) {
                var original = port(face, false); var block = new BlockPos(2, 3, -4);
                var transform = new StructureTransform(new BlockPos(11, 8, 6), axis, rotation, mirror);
                var transformed = original.transform(transform);
                close(h, transformed.position(transform.apply(block)), transform.apply(original.position(block)), "All axis rotations and mirrors preserve the native global point");
                close(h, transformed.normal(), transform.applyWithoutOffsetUncentered(original.normal()), "All rotations and mirrors preserve the outward normal");
            }
        h.assertTrue(SteamHosePort.read(new CompoundTag()) == null, "Missing surface data is not a valid attachment");
        var invalid = new CompoundTag(); SteamHosePort.write(invalid, port(Direction.UP, false));
        invalid.getCompound(SteamHosePort.KEY).putDouble("X", Double.NaN);
        h.assertTrue(SteamHosePort.read(invalid) == null, "Malformed optional point cannot send non-finite geometry to the renderer");
        h.succeed();
    }

    private static void moving(GameTestHelper h, boolean large, Direction face) {
        var selected = port(face, false);
        TrainTestFixture.create(h, "flat", large, false, false, selected, setup -> {}, f -> {
            checkCarriagePort(h, f, selected);
            var before = f.entity().position();
            for (int tick = 0; tick < 20; tick++) f.tick(1, false);
            h.assertTrue(before.distanceToSqr(f.entity().position()) > 1e-6, "Port follows a train that actually travelled on its native rail graph");
            checkCarriagePort(h, f, selected); f.reload(); checkCarriagePort(h, f, selected);
            var entity = f.entity(); var cc = (CarriageContraption) entity.getContraption();
            // Native Train.disassemble overwrites the placed wheel's data after restoring the contraption.
            // Turn the stopped fixture to cover that overwrite after different horizontal rotations.
            var wheel = BlockPos.containing(f.train.carriages.getFirst().leadingBogey().getAnchorPosition());
            entity.yaw = entity.getInitialYaw() - 90 * (face.ordinal() % 4); entity.pitch = 0;
            entity.setPos(Vec3.atLowerCornerOf(wheel));
            final StructureTransform transform;
            try {
                var method = com.simibubi.create.content.contraptions.OrientedContraptionEntity.class.getDeclaredMethod("makeStructureTransform");
                method.setAccessible(true); transform = (StructureTransform) method.invoke(entity);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            var localTank = BlockPos.of(cc.getBlocks().get(BlockPos.ZERO).nbt().getCompound("BogeyData").getLong(SteamHoseLinks.ENGINE_KEY));
            var expectedPoint = transform.apply(selected.position(localTank));
            var expectedNormal = transform.applyWithoutOffsetUncentered(selected.normal());
            f.train.speed = 0;
            h.assertTrue(f.train.disassemble(Direction.SOUTH, wheel.north()), "Native train disassembly succeeds after moving and readback");
            h.runAfterDelay(20, () -> {
                var engine = h.getLevel().getBlockEntity(transform.apply(BlockPos.ZERO));
                var link = (SteamHoseLinks.EngineLink) engine; var restored = link.differentialcreate$port();
                h.assertTrue(restored != null && SteamHoseLinks.tank(engine) != null, "Final native bogey-data restoration retains a working precise hose");
                close(h, restored.position(engine.getBlockPos().offset(link.differentialcreate$hose())), expectedPoint, "Native train disassembly retains the rotated material point");
                close(h, restored.normal(), expectedNormal, "Final bogey-data overwrite retains the rotated face");
                f.finish();
            });
        });
    }

    private static void checkCarriagePort(GameTestHelper h, TrainTestFixture f, SteamHosePort expected) {
        var carriage = f.train.carriages.getFirst();
        closePort(h, SteamHosePort.read(carriage.leadingBogey().bogeyData), expected, "Live carriage bogey retains the clicked surface point");
        var tag = f.entity().getContraption().getBlocks().get(BlockPos.ZERO).nbt();
        closePort(h, SteamHosePort.read(tag.getCompound("BogeyData")), expected, "Captured moving block and native train readback retain the surface point");
    }

    private static void close(GameTestHelper h, Vec3 actual, Vec3 expected, String message) {
        h.assertTrue(actual.distanceToSqr(expected) < 1e-12, message + ": " + actual + " / " + expected);
    }

    private static void closePort(GameTestHelper h, SteamHosePort actual, SteamHosePort expected, String message) {
        h.assertTrue(actual != null && actual.face() == expected.face(), message);
        // A world-space hit loses a few low bits when the test origin is millions of blocks away.
        close(h, actual.point(), expected.point(), message);
    }
}

package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.kinetics.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsMovingInteraction;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real button interactions preserve carrier motion and agree with preview actor selection. */
final class ContraptionControlsTests {
    static final BlockPos POS = new BlockPos(6, 8, 12);
    static void pin(GameTestHelper h) {
        var b = h.getBounds();
        for (int x = (((int)b.minX) >> 4) - 1; x <= (((int)b.maxX) >> 4) + 1; x++)
            for (int z = (((int)b.minZ) >> 4) - 1; z <= (((int)b.maxZ) >> 4) + 1; z++) h.getLevel().setChunkForced(x, z, true);
    }
    static void speed(MechanicalBearingBlockEntity be, double rpm) {
        var n = (INetworkDynamics)be.getOrCreateNetwork();
        n.differentialcreate$setSpeed(rpm / n.differentialcreate$getRatios().get(be));
    }
    static void clicks(GameTestHelper h, boolean partial) {
        pin(h);
        h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int y = 0; y < 4; y++) h.setBlock(POS.east().above(y), Blocks.SLIME_BLOCK);
        h.setBlock(POS.east().above(4), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        for (int y : new int[]{2,3}) {
            var p = POS.east().above(y).south();
            h.setBlock(p, AllBlocks.CONTRAPTION_CONTROLS.getDefaultState());
            ((ContraptionControlsBlockEntity)h.getBlockEntity(p)).filtering.setFilter(AllBlocks.MECHANICAL_DRILL.asStack());
        }
        h.runAfterDelay(5, () -> {
            MechanicalBearingBlockEntity be = h.getBlockEntity(POS);
            be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
            var root = be.getMovedContraption();
            var actors = root.getContraption().getActors();
            var drill = actors.stream().filter(a -> AllBlocks.MECHANICAL_DRILL.has(a.getLeft().state())).findFirst().orElseThrow().getRight();
            var buttons = actors.stream().filter(a -> AllBlocks.CONTRAPTION_CONTROLS.has(a.getLeft().state())).toList();
            h.assertTrue(buttons.size() == 2, "both actual controls assembled");
            if (partial) h.setBlock(POS.east(2).above(4), Blocks.STONE);
            speed(be, partial ? 1 : 16); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
            double beforeSpeed = ActorFeed.speed(be), beforeAngle = root.getAngle(1);
            double paid = drill.data.getDouble("DCActorTravel");
            if (partial) h.assertTrue(paid > 0 && beforeSpeed == 0, "weak coasting structure physically stops with a partial cut");
            else h.assertTrue(Math.abs(beforeSpeed) > 0 && Math.abs(beforeAngle) > 0, "structure already rotating before button click");
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var click = new ContraptionControlsMovingInteraction();
            h.assertTrue(click.handlePlayerInteraction(player, InteractionHand.MAIN_HAND, buttons.getFirst().getLeft().pos(), root), "native click accepted");
            h.assertTrue(drill.disabled, "native button disables drill");
            h.assertTrue(ActorFeed.speed(be) == beforeSpeed && root.getAngle(1) == beforeAngle, "click injects no speed or angle change");
            h.assertTrue(!drill.data.contains("BreakingPos") && !drill.data.contains("DCActorTravel"), "native click cancels partial work");
            h.assertTrue(ContraptionActorWork.of(drill).torque(ContraptionPhysics.drive(drill)) == 0, "disabled drill has no work load");
            for (int i = 0; i < 5; i++) { root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick(); }
            double disabledSpeed = ActorFeed.speed(be), disabledAngle = root.getAngle(1);
            if (!partial) h.assertTrue(Math.abs(disabledAngle - beforeAngle) > .01, "structure continues rotating with tools disabled");
            h.assertTrue(click.handlePlayerInteraction(player, InteractionHand.MAIN_HAND, buttons.getLast().getLeft().pos(), root), "second same-filter control click accepted");
            h.assertTrue(!drill.disabled, "another control of the same type reenables the drill through shared state");
            DifferentialCreate.LOGGER.info("DC_CONTROL_SEMANTICS partial={} speedBefore={} angleBefore={} paid={} disabledSpeed={} disabledAngle={} sharedSecondControlEnabled={}", partial, beforeSpeed, beforeAngle, paid, disabledSpeed, disabledAngle, !drill.disabled);
            root.setAngle(0); be.disassemble(); h.succeed();
        });
    }

    static void sharedSelection(GameTestHelper h) {
        h.setBlock(POS, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.setBlock(POS.above(), Blocks.SLIME_BLOCK);
        h.setBlock(POS.above(2), Blocks.SLIME_BLOCK);
        h.setBlock(POS.above().east(), AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        h.setBlock(POS.above().west(), AllBlocks.MECHANICAL_HARVESTER.getDefaultState());
        var positions = java.util.List.of(POS.above().north(), POS.above().south(), POS.above(3), POS.above(2).south());
        var filters = java.util.List.of(ItemStack.EMPTY, AllBlocks.MECHANICAL_DRILL.asStack(), AllBlocks.MECHANICAL_DRILL.asStack(), AllBlocks.MECHANICAL_HARVESTER.asStack());
        for (int i = 0; i < positions.size(); i++) {
            h.setBlock(positions.get(i), AllBlocks.CONTRAPTION_CONTROLS.getDefaultState());
            ((ContraptionControlsBlockEntity)h.getBlockEntity(positions.get(i))).filtering.setFilter(filters.get(i));
        }
        h.runAfterDelay(5, () -> {
            for (int seed = 0; seed < 3; seed++) {
                // Reassemble identical blocks, including initially disabled all/type filters.
                for (int i = 0; i < positions.size(); i++) {
                    var control = (ContraptionControlsBlockEntity)h.getBlockEntity(positions.get(i));
                    control.disabled = seed == 1 ? i == 0 : seed == 2 && i > 0;
                }
                MechanicalBearingBlockEntity be = h.getBlockEntity(POS); be.assemble(); NetworkTopology.mark(be); NetworkTopology.flush();
                var root = be.getMovedContraption(); var nativeState = root.getContraption();
                var preview = new com.simibubi.create.content.contraptions.mounted.MountedContraption();
                for (var actor : nativeState.getActors()) {
                    var ctx = new com.simibubi.create.content.contraptions.behaviour.MovementContext(h.getLevel(), actor.getLeft(), preview);
                    preview.getActors().add(org.apache.commons.lang3.tuple.MutablePair.of(actor.getLeft(), ctx));
                }
                for (var filter : nativeState.getDisabledActors()) { preview.getDisabledActors().add(filter.copy()); preview.setActorsActive(filter, false); }
                var player = h.makeMockPlayer(GameType.SURVIVAL);
                var interaction = new ContraptionControlsMovingInteraction();
                var buttons = nativeState.getActors().stream().filter(a -> AllBlocks.CONTRAPTION_CONTROLS.has(a.getLeft().state())).toList();
                h.assertTrue(buttons.size() == 4, "all four controls are part of the real assembly");
                var types = new java.util.HashMap<Integer, BlockPos>();
                for (int i = 0; i < positions.size(); i++) {
                    var local = positions.get(i).subtract(POS.above());
                    h.assertTrue(nativeState.getActorAt(local) != null, "control has the expected local assembly position");
                    types.put(i, local);
                }
                speed(be, 16); root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
                for (int button : new int[]{0, 1, 2, 3, 0, 0, 1, 3, 2, 0}) {
                    double beforeSpeed = ActorFeed.speed(be), beforeAngle = root.getAngle(1);
                    interaction.handlePlayerInteraction(player, InteractionHand.MAIN_HAND, types.get(button), root);
                    ActorControlState.toggle(preview, filters.get(button));
                    h.assertTrue(ActorFeed.speed(be) == beforeSpeed && root.getAngle(1) == beforeAngle, "controls do not inject a speed or pose change");
                    for (var actor : nativeState.getActors()) {
                        var shadow = preview.getActorAt(actor.getLeft().pos()).getRight();
                        h.assertTrue(actor.getRight().disabled == shadow.disabled, "preview and native click disagree for " + actor.getLeft().state() + " seed=" + seed + " button=" + button);
                    }
                    h.assertTrue(nativeState.getDisabledActors().size() == preview.getDisabledActors().size(), "shared all/type filter count agrees");
                    for (var filter : nativeState.getDisabledActors()) h.assertTrue(preview.isActorTypeDisabled(filter), "shared actor filter agrees");
                    root.tickActors(); MachiningClock.advance(h.getLevel()); be.tick();
                    h.assertTrue(root.getAngle(1) > beforeAngle, "all/type and duplicate-control clicks preserve carrier motion");
                }
                root.setAngle(0); be.disassemble();
            }
            h.succeed();
        });
    }
}

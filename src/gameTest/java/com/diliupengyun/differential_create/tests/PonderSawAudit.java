package com.diliupengyun.differential_create.tests;

import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Material presentation must include its source, return flight and deliberate removal. */
final class PonderSawAudit {
    private boolean introducedLog, returnFlight, returned, planks, filteredStone;
    private boolean oakSeen, oakFelled, oakDrops, jungleSeen, jungleFelled, jungleDrops;
    private int jungleBlocks;
    private Vec3 lastStrippedPosition;
    private final java.util.Set<String> failures = new java.util.LinkedHashSet<>();

    void sample(PonderScene scene) {
        if (scene.getId().toString().equals("create:mechanical_saw_breaker")) { trees(scene); return; }
        if (!scene.getId().toString().equals("create:mechanical_saw_processing")) return;
        int time = scene.getCurrentTime();
        int logs = 0, stripped = 0, boards = 0;
        for (var entity : scene.getWorld().getEntityList()) {
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) continue;
            var stack = item.getItem();
            if (stack.is(Items.OAK_LOG)) logs += stack.getCount();
            if (stack.is(Items.STRIPPED_OAK_LOG)) {
                stripped += stack.getCount();
                lastStrippedPosition = item.position();
                returnFlight |= item.getDeltaMovement().x < -.001;
            }
            if (stack.is(Items.OAK_PLANKS)) boards += stack.getCount();
        }
        if (time >= 75 && time <= 100) {
            introducedLog |= logs == 1;
            if (logs != 1) failures.add("The first pickup/throw demonstration has no single visible oak log");
        }
        var pos = new BlockPos(2, 1, 2);
        if (scene.getWorld().getBlockEntity(pos) instanceof SawBlockEntity saw) {
            if (saw.inventory.getStackInSlot(0).is(Items.STRIPPED_OAK_LOG) && !returned) {
                returned = true;
                if (lastStrippedPosition == null || Math.abs(lastStrippedPosition.x - (pos.getX() + .5)) > .65
                    || Math.abs(lastStrippedPosition.z - (pos.getZ() + .5)) > .65)
                    failures.add("The stripped log teleports from its landing position into the saw: " + lastStrippedPosition);
            }
            for (int slot = 0; slot < saw.inventory.getSlots(); slot++) {
                var stack = saw.inventory.getStackInSlot(slot);
                if (stack.is(Items.OAK_PLANKS)) { boards += stack.getCount(); planks = true; }
                if (time > 520 && stack.is(Items.STONE_BRICKS)) filteredStone = true;
            }
        }
        if (boards > 0 && boards != 6) failures.add("The single stripped log did not yield exactly six boards: " + boards);
        if (time >= 350 && (logs > 0 || stripped > 0 || boards > 0))
            failures.add("Wood remains on the stage during the stone/belt demonstration");
        if (time >= 160 && time <= 260 && stripped != 1)
            failures.add("The actual stripped result disappears or duplicates before its return");
    }

    private void trees(PonderScene scene) {
        var world = scene.getWorld();
        var trunk = new BlockPos(2, 1, 2);
        oakSeen |= world.getBlockState(trunk).is(net.minecraft.world.level.block.Blocks.OAK_LOG);
        jungleSeen |= world.getBlockState(trunk).is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG);
        int blocks = (int) world.getBlockMap().values().stream().filter(state -> state.is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG)).count();
        jungleBlocks = Math.max(jungleBlocks, blocks);
        if (!jungleSeen && oakSeen && world.getBlockState(trunk).isAir() && world.getBlockState(trunk.above()).isAir()) oakFelled = true;
        if (jungleSeen && blocks == 0) jungleFelled = true;
        int jungle = 0;
        for (var entity : world.getEntityList()) if (entity instanceof ItemEntity item && item.isAlive()) {
            oakDrops |= item.getItem().is(Items.OAK_LOG);
            if (item.getItem().is(Items.JUNGLE_LOG)) jungle += item.getItem().getCount();
        }
        // Selections are clipped to the native stage bounds. Count the actual
        // placed trunk, excluding the three roots removed by the operator diagram.
        jungleDrops |= jungleBlocks > 3 && jungle == jungleBlocks - 3;
    }

    void verify(PonderScene scene) {
        if (scene.getId().toString().equals("create:mechanical_saw_breaker")) {
            if (!oakSeen || !oakFelled || !oakDrops || !jungleSeen || !jungleFelled || !jungleDrops)
                throw new AssertionError("The stationary saw never actually felled both native trees: oak=" + oakSeen + "/" + oakFelled
                    + "/" + oakDrops + ", jungle=" + jungleSeen + "/" + jungleFelled + "/" + jungleDrops);
            return;
        }
        if (!scene.getId().toString().equals("create:mechanical_saw_processing")) return;
        if (!introducedLog || !returnFlight || !returned || !planks || !filteredStone)
            failures.add("Incomplete saw flow: input=" + introducedLog + ", return flight=" + returnFlight
                + ", returned=" + returned + ", boards=" + planks + ", filtered stone=" + filteredStone);
        if (!failures.isEmpty()) throw new AssertionError("Saw presentation: " + failures);
    }
}

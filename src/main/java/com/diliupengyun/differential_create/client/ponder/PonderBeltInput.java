package com.diliupengyun.differential_create.client.ponder;

import java.util.HashSet;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.foundation.ponder.element.BeltItemElement;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.ElementLinkImpl;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

/** Retry a scripted delivery until the moving belt has room; only track the accepted item. */
public final class PonderBeltInput {
    private PonderBeltInput() {}
    /** Reinsert an actual previously ejected result rather than spawning a duplicate. */
    public static void retrieve(CreateSceneBuilder builder, BlockPos pos, Direction side, ItemStack requested) {
        builder.addInstruction(new TickingInstruction(false, 160) {
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                var input = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(scene.getWorld(), pos, DirectBeltInputBehaviour.TYPE);
                if (input == null) return;
                for (var entity : java.util.List.copyOf(scene.getWorld().getEntityList())) {
                    if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity item) || !item.isAlive()
                        || !ItemStack.isSameItemSameComponents(item.getItem(), requested)) continue;
                    var left = input.handleInsertion(item.getItem().copy(), side.getOpposite(), false);
                    if (left.getCount() == item.getItem().getCount()) continue;
                    if (left.isEmpty()) item.discard(); else item.setItem(left);
                    remainingTicks = 0; break;
                }
            }
        });
    }

    public static ElementLink<BeltItemElement> insert(CreateSceneBuilder builder, BlockPos pos,
        Direction side, ItemStack stack) {
        var link = new ElementLinkImpl<>(BeltItemElement.class);
        builder.addInstruction(new TickingInstruction(false, 100) {
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                if (!(scene.getWorld().getBlockEntity(pos) instanceof BeltBlockEntity belt)) return;
                var controller = belt.getControllerBE();
                var input = belt.getBehaviour(DirectBeltInputBehaviour.TYPE);
                if (controller == null || input == null) return;
                var inventory = controller.getInventory();
                var before = new HashSet<>(inventory.getTransportedItems());
                if (!input.handleInsertion(stack.copy(), side.getOpposite(), false).isEmpty()) return;
                controller.tick();
                for (var item : inventory.getTransportedItems()) if (!before.contains(item)) {
                    var tracker = new BeltItemElement(item);
                    scene.addElement(tracker);
                    scene.linkElement(tracker, link);
                    break;
                }
                remainingTicks = 0;
            }
        });
        return link;
    }

    /** Observe the actual recipe output; this cue does not create or replace inventory. */
    public static ElementLink<BeltItemElement> convert(CreateSceneBuilder builder, BlockPos pos,
        Item input, ItemStack product) {
        var link = new ElementLinkImpl<>(BeltItemElement.class);
        builder.addInstruction(new TickingInstruction(false, 80) {
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                if (!(scene.getWorld().getBlockEntity(pos) instanceof SmartBlockEntity be)) return;
                var handler = be.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
                if (handler == null) return;
                boolean[] converted = {false};
                handler.handleCenteredProcessingOnAllItems(.03f, item -> {
                    if (!converted[0] && ItemStack.isSameItemSameComponents(item.stack, product)) {
                        var tracker = new BeltItemElement(item);
                        scene.addElement(tracker);
                        scene.linkElement(tracker, link);
                        converted[0] = true;
                    }
                    return TransportedResult.doNothing();
                });
                if (converted[0]) remainingTicks = 0;
            }
        });
        return link;
    }

    /** A slower belt may reach the machine after the original storyboard's hold instruction. */
    public static void holdAt(CreateSceneBuilder builder, BlockPos pos, ElementLink<BeltItemElement> link) {
        builder.addInstruction(new TickingInstruction(false, 80) {
            @Override public void tick(PonderScene scene) {
                super.tick(scene);
                if (!(scene.getWorld().getBlockEntity(pos) instanceof SmartBlockEntity be)) return;
                var handler = be.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
                var tracker = scene.resolve(link);
                if (handler == null || tracker == null) return;
                tracker.ifPresent(target -> handler.handleCenteredProcessingOnAllItems(.03f, item -> {
                    if (item == target) {
                        item.locked = true;
                        remainingTicks = 0;
                    }
                    return TransportedResult.doNothing();
                }));
            }
        });
    }
}

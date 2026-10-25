package com.diliupengyun.differential_create.client.ponder;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import com.diliupengyun.differential_create.ponder.PonderLootPayloads;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/** Ponder has recipes, but vanilla does not synchronize block loot tables to clients. */
public final class PonderLoot {
    private record Pending(WeakReference<PonderLevel> world, Consumer<ItemStack> collect) {}
    private static final Map<Integer, Pending> pending = new HashMap<>();
    private static int nextTicket;
    private PonderLoot() {}
    public static void collect(PonderLevel world, BlockPos pos, BlockState state, ItemStack tool, Consumer<ItemStack> collect) {
        var minecraft = Minecraft.getInstance();
        long seed = world.random.nextLong();
        var server = minecraft.getSingleplayerServer();
        if (server != null) {
            // Evaluate on the server thread, then deliver into the virtual inventory
            // on this client thread. No block or inventory in the save is touched.
            var level = server.getLevel(world.dimension());
            if (level == null) level = server.overworld();
            var lootWorld = level; var inputTool = tool.copy();
            server.submit(() -> PonderLootPayloads.sample(lootWorld, state, pos, inputTool, seed)).join().forEach(collect);
        } else if (minecraft.getConnection() != null) {
            int ticket = ++nextTicket;
            pending.put(ticket, new Pending(new WeakReference<>(world), collect));
            PacketDistributor.sendToServer(new PonderLootPayloads.Request(ticket, Block.getId(state), pos.immutable(),
                BuiltInRegistries.ITEM.getId(tool.getItem()), seed));
        }
    }
    public static void accept(PonderLootPayloads.Result result) {
        var request = pending.remove(result.ticket());
        if (request != null && request.world().get() != null) result.drops().stream().filter(stack -> !stack.isEmpty()).forEach(request.collect());
    }
    public static void reset(PonderLevel world) { pending.values().removeIf(request -> request.world().get() == null || request.world().get() == world); }
}

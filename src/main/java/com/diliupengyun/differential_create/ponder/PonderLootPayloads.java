package com.diliupengyun.differential_create.ponder;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.DifferentialCreate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Read-only loot evaluation. Returned items exist exclusively in the requesting tutorial. */
public final class PonderLootPayloads {
    private static final ThreadLocal<net.minecraft.util.RandomSource> random = new ThreadLocal<>();
    private PonderLootPayloads() {}
    public static net.minecraft.util.RandomSource random() { return random.get(); }
    public static List<ItemStack> sample(ServerLevel world, BlockState state, BlockPos pos, ItemStack tool, long seed) {
        var previous = random.get(); random.set(net.minecraft.util.RandomSource.create(seed));
        try { return Block.getDrops(state, world, pos, null, null, tool).stream().map(ItemStack::copy).toList(); }
        finally { if (previous == null) random.remove(); else random.set(previous); }
    }
    public record Request(int ticket, int state, BlockPos pos, int tool, long seed) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "ponder_loot_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.ticket); buf.writeVarInt(p.state); buf.writeBlockPos(p.pos); buf.writeVarInt(p.tool); buf.writeLong(p.seed);
        }, buf -> new Request(buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(), buf.readVarInt(), buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Request p, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) return;
                var state = Block.stateById(p.state);
                var item = BuiltInRegistries.ITEM.byId(p.tool);
                if (item == null || Block.getId(state) != p.state || Math.abs((long) p.pos.getX()) > 128 || Math.abs((long) p.pos.getZ()) > 128
                    || p.pos.getY() < -64 || p.pos.getY() > 256) { context.reply(new Result(p.ticket, List.of())); return; }
                var tool = item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
                context.reply(new Result(p.ticket, sample(player.serverLevel(), state, p.pos, tool, p.seed)));
            });
        }
    }
    public record Result(int ticket, List<ItemStack> drops) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "ponder_loot_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.ticket); buf.writeVarInt(p.drops.size());
            for (var stack : p.drops) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }, buf -> {
            int ticket = buf.readVarInt(), count = buf.readVarInt();
            if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid preview loot count");
            var items = new ArrayList<ItemStack>(count);
            for (int i = 0; i < count; i++) items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            return new Result(ticket, items);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Result p, IPayloadContext context) {
            context.enqueueWork(() -> com.diliupengyun.differential_create.client.ponder.PonderLoot.accept(p));
        }
    }
}

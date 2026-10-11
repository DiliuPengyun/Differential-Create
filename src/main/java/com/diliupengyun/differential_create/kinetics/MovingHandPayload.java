package com.diliupengyun.differential_create.kinetics;

import java.util.*;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientMovingHands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MovingHandPayload(ResourceLocation dimension, int entityId, List<Hand> hands) implements CustomPacketPayload {
    public record Hand(BlockPos localPos, float extension, float rpm, double angle, int phase, boolean holding, String failure,
                       CompoundTag held, BlockPos controller, float feedSpeed, double inertia, double brakeHeat) {}
    public static final Type<MovingHandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "moving_hand"));
    public static final StreamCodec<FriendlyByteBuf, MovingHandPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeResourceLocation(p.dimension); buf.writeVarInt(p.entityId); buf.writeVarInt(p.hands.size());
        for (var h : p.hands) {
            buf.writeBlockPos(h.localPos); buf.writeFloat(h.extension); buf.writeFloat(h.rpm); buf.writeDouble(h.angle);
            buf.writeVarInt(h.phase); buf.writeBoolean(h.holding); buf.writeUtf(h.failure, 64); buf.writeNbt(h.held);
            buf.writeBoolean(h.controller != null);
            if (h.controller != null) { buf.writeBlockPos(h.controller); buf.writeFloat(h.feedSpeed); buf.writeDouble(h.inertia); buf.writeDouble(h.brakeHeat); }
        }
    }, buf -> {
        var dimension = buf.readResourceLocation(); int entity = buf.readVarInt(), count = buf.readVarInt();
        if (entity < 0 || count < 0 || count > 32768) throw new IllegalArgumentException("Invalid moving-hand packet");
        var hands = new ArrayList<Hand>(count);
        for (int i = 0; i < count; i++) {
            var pos = buf.readBlockPos(); float extension = buf.readFloat(), rpm = buf.readFloat(); double angle = buf.readDouble();
            int phase = buf.readVarInt(); boolean holding = buf.readBoolean(); String failure = buf.readUtf(64);
            var held = buf.readNbt(); boolean controller = buf.readBoolean();
            var at = controller ? buf.readBlockPos() : null;
            float feed = controller ? buf.readFloat() : 0;
            double inertia = controller ? buf.readDouble() : 0, heat = controller ? buf.readDouble() : 0;
            if (!Float.isFinite(extension) || !Float.isFinite(rpm) || !Double.isFinite(angle) || !Float.isFinite(feed)
                || !Double.isFinite(inertia) || !Double.isFinite(heat) || extension < 0 || extension > .5 || phase < 0 || phase > 2)
                throw new IllegalArgumentException("Invalid moving-hand state");
            hands.add(new Hand(pos, extension, rpm, angle, phase, holding, failure, held == null ? new CompoundTag() : held, at, feed, inertia, heat));
        }
        return new MovingHandPayload(dimension, entity, hands);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(MovingHandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMovingHands.apply(payload));
    }
}

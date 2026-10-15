package com.diliupengyun.differential_create.trains;

import java.util.UUID;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.Create;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Commands are accepted only from this train's current controlling player. */
public record TrainGearPayload(UUID train, int step) implements CustomPacketPayload {
    public static final Type<TrainGearPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "train_gear"));
    public static final StreamCodec<FriendlyByteBuf, TrainGearPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> { buf.writeUUID(p.train); buf.writeVarInt(p.step); }, buf -> new TrainGearPayload(buf.readUUID(), buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(TrainGearPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (p.step != -1 && p.step != 1) return;
            var player = context.player(); var train = Create.RAILWAYS.trains.get(p.train); if (train == null) return;
            boolean[] authorized = {false};
            for (var c : train.carriages) c.forEachPresentEntity(entity -> {
                if (entity.level() == player.level() && entity.getControllingPlayer().filter(player.getUUID()::equals).isPresent()) authorized[0] = true;
            });
            if (authorized[0]) TrainPhysics.shift(train, TrainMechanics.stepGear(TrainPhysics.state(train).gear, p.step, false));
        });
    }
}

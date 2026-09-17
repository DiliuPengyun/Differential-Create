package com.diliupengyun.differential_create.kinetics;

import java.util.ArrayList;
import java.util.List;
import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Actual local rotor states; clients never reconstruct an elastic network from one average speed. */
public record RotorStatePayload(ResourceLocation dimension, long networkId, double energy, LoadFactor.Reading loadFactor, List<Rotor> rotors)
    implements CustomPacketPayload {
    public record Rotor(BlockPos pos, float speed, double angle, float torque) {}
    public static final Type<RotorStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "rotor_state"));
    public static final StreamCodec<FriendlyByteBuf, RotorStatePayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
        buf.writeResourceLocation(p.dimension); buf.writeVarLong(p.networkId); buf.writeDouble(p.energy);
        buf.writeEnum(p.loadFactor.status()); buf.writeDouble(p.loadFactor.fraction());
        buf.writeVarInt(p.rotors.size());
        for (Rotor r : p.rotors) {
            buf.writeBlockPos(r.pos); buf.writeFloat(r.speed); buf.writeDouble(r.angle); buf.writeFloat(r.torque);
        }
    }, buf -> {
        var dimension = buf.readResourceLocation(); long id = buf.readVarLong(); double energy = buf.readDouble();
        var load = new LoadFactor.Reading(buf.readEnum(LoadFactor.Status.class), buf.readDouble());
        int size = buf.readVarInt();
        if (size < 0 || size > 32768) throw new IllegalArgumentException("Invalid rotor packet size");
        List<Rotor> rotors = new ArrayList<>(size);
        for (int i = 0; i < size; i++) rotors.add(new Rotor(buf.readBlockPos(), buf.readFloat(), buf.readDouble(), buf.readFloat()));
        return new RotorStatePayload(dimension, id, energy, load, rotors);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(RotorStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientNetworkSpeeds.applyRotors(ResourceKey.create(Registries.DIMENSION, payload.dimension), payload));
    }
}

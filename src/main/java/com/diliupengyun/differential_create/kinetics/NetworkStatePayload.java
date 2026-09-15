package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Full equation coefficients: clients use the same zero-crossing and drag solver. */
public record NetworkStatePayload(ResourceLocation dimension, long networkId, float speed,
	float torque, float damping, float load, float inertia, float drag) implements CustomPacketPayload {
	public static final Type<NetworkStatePayload> TYPE = new Type<>(
		ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "network_state"));
	public static final StreamCodec<FriendlyByteBuf, NetworkStatePayload> STREAM_CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeResourceLocation(p.dimension);
			buf.writeVarLong(p.networkId);
			buf.writeFloat(p.speed);
			buf.writeFloat(p.torque);
			buf.writeFloat(p.damping);
			buf.writeFloat(p.load);
			buf.writeFloat(p.inertia);
			buf.writeFloat(p.drag);
		}, buf -> new NetworkStatePayload(buf.readResourceLocation(), buf.readVarLong(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
	public static void handle(NetworkStatePayload payload, IPayloadContext context) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, payload.dimension);
		context.enqueueWork(() -> ClientNetworkSpeeds.applyState(dimension, payload));
	}
}
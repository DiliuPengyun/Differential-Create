package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * State packet: the dynamic state of one kinetic network, sent at a low
 * rate or on material changes. The client extrapolates the exact same
 * curve the server integrates (linear exact step + fan-drag exact step),
 * so per-network packet rates of 1–2 Hz are visually seamless and — more
 * importantly — every member of a network stays strictly proportional to
 * the shared ω.
 *
 * @param speed      current network speed ω (RPM)
 * @param equilibrium speed the network converges to ω* (fan-refined)
 * @param invTau     linear decay rate K/J (1/min); 0 when not applicable
 * @param linearDecay coasting decay rate L/J (1/min) for sourceless networks
 * @param fanOverJ   quadratic fan drag C/J (1/(min·RPM))
 */
public record NetworkStatePayload(ResourceLocation dimension, long networkId, float speed, float equilibrium,
	float invTau, float linearDecay, float fanOverJ) implements CustomPacketPayload {

	public static final Type<NetworkStatePayload> TYPE = new Type<>(
		ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "network_state"));

	public static final StreamCodec<FriendlyByteBuf, NetworkStatePayload> STREAM_CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeResourceLocation(payload.dimension);
			buf.writeVarLong(payload.networkId);
			buf.writeFloat(payload.speed);
			buf.writeFloat(payload.equilibrium);
			buf.writeFloat(payload.invTau);
			buf.writeFloat(payload.linearDecay);
			buf.writeFloat(payload.fanOverJ);
		},
		buf -> new NetworkStatePayload(buf.readResourceLocation(), buf.readVarLong(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Applied on the client thread. */
	public static void handle(NetworkStatePayload payload, IPayloadContext context) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, payload.dimension);
		context.enqueueWork(() -> ClientNetworkSpeeds.applyState(dimension, payload));
	}

}

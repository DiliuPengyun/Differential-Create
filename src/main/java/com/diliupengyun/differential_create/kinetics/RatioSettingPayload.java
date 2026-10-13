package com.diliupengyun.differential_create.kinetics;

import com.diliupengyun.differential_create.DifferentialCreate;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RatioSettingPayload(BlockPos pos, int value) implements CustomPacketPayload {
    public static final Type<RatioSettingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DifferentialCreate.MODID, "ratio_setting"));
    public static final StreamCodec<FriendlyByteBuf, RatioSettingPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> { buf.writeBlockPos(p.pos); buf.writeInt(p.value); }, buf -> new RatioSettingPayload(buf.readBlockPos(), buf.readInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(RatioSettingPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (p.value < -256 || p.value > 256 || !player.mayBuild() || !player.level().isLoaded(p.pos)
                || player.position().distanceToSqr(p.pos.getCenter()) > 64) return;
            if (player.level().getBlockEntity(p.pos) instanceof SpeedControllerBlockEntity controller
                && controller.targetSpeed instanceof RatioValueBehaviour) controller.targetSpeed.setValue(p.value);
        });
    }
}

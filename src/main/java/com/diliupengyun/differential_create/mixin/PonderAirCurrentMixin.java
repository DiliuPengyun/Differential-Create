package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Opted-in lessons run actual airflow/recipe processing on virtual depots and belts. */
@Mixin(AirCurrent.class)
public abstract class PonderAirCurrentMixin {
    @Shadow @Final public IAirCurrentSource source;

    @WrapOperation(method = "lambda$tickAffectedHandlers$0", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/level/Level;isClientSide:Z", opcode = Opcodes.GETFIELD))
    private boolean differentialcreate$processVirtualItems(Level world, Operation<Boolean> original) {
        return original.call(world) && !(world instanceof PonderLevel level
            && PonderKinetics.of(level).processesFan(source.getAirCurrentPos()));
    }
}

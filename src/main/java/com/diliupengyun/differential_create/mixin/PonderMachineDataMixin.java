package com.diliupengyun.differential_create.mixin;

import java.util.function.Consumer;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Storyboards configure machines, but cannot overwrite calculated progress or docking state. */
@Mixin(targets = "net.createmod.ponder.foundation.PonderSceneBuilder$PonderWorldInstructions")
public abstract class PonderMachineDataMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "incrementBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$actualBreaking(net.minecraft.core.BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) { ci.cancel(); }
    @ModifyVariable(method = "modifyBlockEntityNBT(Lnet/createmod/ponder/api/scene/Selection;Ljava/lang/Class;Ljava/util/function/Consumer;Z)V",
        at = @At("HEAD"), argsOnly = true)
    private Consumer<CompoundTag> differentialcreate$inputsOnly(Consumer<CompoundTag> action,
        Selection selection, Class<? extends BlockEntity> type, Consumer<CompoundTag> original, boolean redraw) {
        boolean timed = com.simibubi.create.content.fluids.spout.SpoutBlockEntity.class.isAssignableFrom(type);
        boolean docking = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class.isAssignableFrom(type);
        boolean depot = com.simibubi.create.content.logistics.depot.DepotBlockEntity.class.isAssignableFrom(type);
        boolean basin = com.simibubi.create.content.processing.basin.BasinBlockEntity.class.isAssignableFrom(type);
        boolean ejector = com.simibubi.create.content.logistics.depot.EjectorBlockEntity.class.isAssignableFrom(type);
        if (!timed && !docking && !depot && !basin && !ejector) return action;
        return data -> {
            var before = data.copy(); action.accept(data);
            for (var key : docking ? java.util.List.of("Timer", "Distance", "DCSession") : depot ? java.util.List.of("HeldItem")
                : basin ? java.util.List.of("VisualizedItems", "VisualizedFluids")
                : ejector ? java.util.List.of("State", "ForceAngle", "DCEjectorChargeTravel", "LaunchedItems") : java.util.List.of("ProcessingTicks", "Splash")) {
                if (before.contains(key)) data.put(key, before.get(key).copy()); else data.remove(key);
            }
        };
    }
}

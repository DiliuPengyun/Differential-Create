package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.PonderTrainScenes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.simibubi.create.infrastructure.ponder.scenes.trains.TrainScenes;
import com.simibubi.create.infrastructure.ponder.scenes.trains.TrainStationScenes;
import com.simibubi.create.infrastructure.ponder.scenes.trains.TrainSignalScenes;
import com.simibubi.create.infrastructure.ponder.scenes.trains.TrackObserverScenes;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({TrainScenes.class, TrainStationScenes.class, TrainSignalScenes.class, TrackObserverScenes.class})
public abstract class TrainStoryboardMotionMixin {
    @WrapOperation(method = "observe", require = 0, at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$SpecialInstructions;createBirb(Lnet/minecraft/world/phys/Vec3;Ljava/util/function/Supplier;)Lnet/createmod/ponder/api/element/ElementLink;"))
    private static ElementLink<ParrotElement> differentialcreate$observerSeat(CreateSceneBuilder.SpecialInstructions special,
        Vec3 location, java.util.function.Supplier<? extends net.createmod.ponder.api.element.ParrotPose> pose,
        Operation<ElementLink<ParrotElement>> original) {
        return original.call(special, location.add(-5, 0, 0), pose);
    }

    @Inject(method = "observe", at = @At("HEAD"), require = 0)
    private static void differentialcreate$observerCargo(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci) {
        // This template predates the current fluid NBT format. The matching
        // carriage must carry water, rather than relying on a scripted pulse.
        new CreateSceneBuilder(builder).world().modifyBlockEntity(util.grid().at(9, 3, 4),
            com.simibubi.create.content.fluids.tank.FluidTankBlockEntity.class,
            tank -> tank.getTankInventory().setFluid(new net.neoforged.neoforge.fluids.FluidStack(
                net.minecraft.world.level.material.Fluids.WATER, 4000)));
    }
    @Unique private static PonderTrainScenes differentialcreate$motion(LocalRef<PonderTrainScenes> ref) {
        if (ref.get() == null) ref.set(new PonderTrainScenes());
        return ref.get();
    }
    @WrapOperation(method = {"assembly", "autoSchedule", "controls", "schedule", "placement", "signaling", "observe"}, require = 0,
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;moveSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V"))
    private static void differentialcreate$move(CreateSceneBuilder.WorldInstructions world, ElementLink<WorldSectionElement> link,
        Vec3 delta, int ticks, Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder,
        @Share("trainMotion") LocalRef<PonderTrainScenes> motion) {
        differentialcreate$motion(motion).move(builder, new CreateSceneBuilder(builder).getScene().getId().getPath(), link, delta, ticks);
    }
    @WrapOperation(method = {"assembly", "autoSchedule", "controls", "schedule", "placement", "signaling", "observe"}, require = 0,
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$SpecialInstructions;moveParrot(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/world/phys/Vec3;I)V"))
    private static void differentialcreate$rider(CreateSceneBuilder.SpecialInstructions special, ElementLink<ParrotElement> link,
        Vec3 delta, int ticks, Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder,
        @Share("trainMotion") LocalRef<PonderTrainScenes> motion) {
        differentialcreate$motion(motion).rider(builder, link, delta, ticks);
    }
    @WrapOperation(method = {"assembly", "autoSchedule", "controls", "schedule", "placement", "signaling", "observe"}, require = 0,
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder;idle(I)V"))
    private static void differentialcreate$arrival(CreateSceneBuilder builder, int ticks, Operation<Void> original,
        @Share("trainMotion") LocalRef<PonderTrainScenes> motion) {
        if (motion.get() != null && motion.get().joinsObserverTrailer(builder.getScene().getId().getPath(), ticks)) return;
        if (motion.get() != null) motion.get().flush(builder);
        original.call(builder, ticks);
        if (motion.get() != null) motion.get().afterIdle(builder, ticks);
    }
    @WrapOperation(method = {"assembly", "autoSchedule", "controls", "schedule", "placement", "signaling", "observe"}, require = 0,
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/ponder/CreateSceneBuilder$WorldInstructions;hideIndependentSection(Lnet/createmod/ponder/api/element/ElementLink;Lnet/minecraft/core/Direction;)V"))
    private static void differentialcreate$depart(CreateSceneBuilder.WorldInstructions world, ElementLink<WorldSectionElement> link,
        Direction direction, Operation<Void> original, @Local(argsOnly = true) SceneBuilder builder,
        @Share("trainMotion") LocalRef<PonderTrainScenes> motion) {
        if (motion.get() != null) motion.get().beforeHide(builder);
        original.call(world, link, direction);
    }
    @Inject(method = {"assembly", "autoSchedule", "controls", "schedule", "placement", "signaling", "observe"}, at = @At("RETURN"), require = 0)
    private static void differentialcreate$finish(SceneBuilder builder, SceneBuildingUtil util, CallbackInfo ci,
        @Share("trainMotion") LocalRef<PonderTrainScenes> motion) {
        if (motion.get() != null) motion.get().finish(builder);
    }
}

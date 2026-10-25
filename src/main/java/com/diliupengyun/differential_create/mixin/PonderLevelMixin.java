package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ponder.IPonderKinetics;
import com.diliupengyun.differential_create.client.ponder.PonderKinetics;
import net.createmod.ponder.api.level.PonderLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PonderLevel.class)
public abstract class PonderLevelMixin implements IPonderKinetics, com.diliupengyun.differential_create.kinetics.PhysicsPreview {
    @Unique private final PonderKinetics differentialcreate$kinetics = new PonderKinetics();
    @Unique private final com.diliupengyun.differential_create.client.ponder.PonderSimulation differentialcreate$simulation =
        new com.diliupengyun.differential_create.client.ponder.PonderSimulation();
    @Override public boolean differentialcreate$physical(net.minecraft.core.BlockPos pos) {
        return differentialcreate$simulation.physical((PonderLevel) (Object) this, pos);
    }
    @Override public long differentialcreate$time() { return differentialcreate$simulation.time(); }
    @Override public net.minecraft.core.BlockPos differentialcreate$relative(net.minecraft.core.BlockPos origin, net.minecraft.core.BlockPos offset) {
        return differentialcreate$simulation.relative((PonderLevel) (Object) this, origin, offset);
    }
    @Override public double differentialcreate$height(net.minecraft.core.BlockPos pos) { return differentialcreate$simulation.height(pos); }
    @Override public double differentialcreate$workTravel(net.minecraft.core.BlockPos pos) { return differentialcreate$kinetics.workTravel(pos); }
    @Override public com.diliupengyun.differential_create.kinetics.ActorDrive differentialcreate$actorDrive(com.simibubi.create.content.contraptions.behaviour.MovementContext context) { return differentialcreate$simulation.actorDrive(context); }
    @Override public boolean differentialcreate$mountedTool(net.minecraft.core.BlockPos pos) { return differentialcreate$simulation.mountedTool(pos); }
    @Override public com.diliupengyun.differential_create.trains.TrainMechanics.Engine differentialcreate$trainBoundary(com.simibubi.create.content.trains.entity.Train train) { return differentialcreate$simulation.trainBoundary(train); }
    public void destroyBlockProgress(int breaker, net.minecraft.core.BlockPos pos, int stage) {
        ((PonderLevel) (Object) this).setBlockBreakingProgress(pos, stage < 0 ? 0 : stage + 1);
    }
    public java.util.List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except,
        net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super net.minecraft.world.entity.Entity> filter) {
        return new java.util.ArrayList<>(((PonderLevel) (Object) this).getEntityList().stream().filter(entity -> entity != except && entity.isAlive()
            && entity.getBoundingBox().intersects(area) && filter.test(entity)).toList());
    }
    public <T extends net.minecraft.world.entity.Entity> java.util.List<T> getEntitiesOfClass(Class<T> type,
        net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super T> filter) {
        return new java.util.ArrayList<>(((PonderLevel) (Object) this).getEntityList().stream().filter(type::isInstance).map(type::cast)
            .filter(entity -> entity.isAlive() && entity.getBoundingBox().intersects(area) && filter.test(entity)).toList());
    }
    @Override public PonderKinetics differentialcreate$kinetics() { return differentialcreate$kinetics; }
    @Override public com.diliupengyun.differential_create.client.ponder.PonderSimulation differentialcreate$simulation() {
        return differentialcreate$simulation;
    }
    @Inject(method = "restore", at = @At("HEAD"))
    private void differentialcreate$rewindPhysics(CallbackInfo ci) {
        differentialcreate$kinetics.reset();
        differentialcreate$simulation.reset((PonderLevel) (Object) this);
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void differentialcreate$advancePhysics(CallbackInfo ci) {
        var world = (PonderLevel) (Object) this;
        differentialcreate$kinetics.hydraulicTorques(differentialcreate$simulation.advance(world));
        differentialcreate$kinetics.tick(world);
    }
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void differentialcreate$redrawSolvedChanges(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
        int flags, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        var world = (PonderLevel) (Object) this;
        if (cir.getReturnValueZ() && world.scene != null)
            world.scene.forEach(net.createmod.ponder.api.element.WorldSectionElement.class, net.createmod.ponder.api.element.WorldSectionElement::queueRedraw);
    }
}

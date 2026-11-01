package com.diliupengyun.differential_create.mixin;

import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldSectionElementImpl.class)
public interface PonderWorldSectionAccessor {
    @Accessor("section") Selection differentialcreate$selection();
    @Accessor("centerOfRotation") net.minecraft.world.phys.Vec3 differentialcreate$centre();
    @Accessor("prevAnimatedRotation") net.minecraft.world.phys.Vec3 differentialcreate$previousRotation();
    @Accessor("prevAnimatedRotation") void differentialcreate$previousRotation(net.minecraft.world.phys.Vec3 value);
    @Accessor("prevAnimatedOffset") net.minecraft.world.phys.Vec3 differentialcreate$previousOffset();
    @Accessor("prevAnimatedOffset") void differentialcreate$previousOffset(net.minecraft.world.phys.Vec3 value);
    @Accessor("stabilizationAnchor") net.minecraft.world.phys.Vec3 differentialcreate$stabilization();
}

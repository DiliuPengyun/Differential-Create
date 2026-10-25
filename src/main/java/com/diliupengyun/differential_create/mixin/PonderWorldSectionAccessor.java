package com.diliupengyun.differential_create.mixin;

import net.createmod.ponder.api.scene.Selection;
import net.createmod.ponder.foundation.element.WorldSectionElementImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldSectionElementImpl.class)
public interface PonderWorldSectionAccessor {
    @Accessor("section") Selection differentialcreate$selection();
    @Accessor("centerOfRotation") net.minecraft.world.phys.Vec3 differentialcreate$centre();
}

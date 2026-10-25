package com.diliupengyun.differential_create.mixin;

import com.simibubi.create.content.contraptions.MountedStorageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MountedStorageManager.class)
public interface PonderMountedStorageAccessor {
    @Invoker("reset") void differentialcreate$reassemble();
}

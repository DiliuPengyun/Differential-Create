package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SawBlockEntity.class)
public interface SawBlockEntityAccessor {
	@Invoker("getRecipes") List<?> differentialcreate$matchingRecipes();
}

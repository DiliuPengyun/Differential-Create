package com.diliupengyun.differential_create.mixin;

import java.util.Map;
import java.util.UUID;
import com.simibubi.create.content.contraptions.Contraption;
import net.createmod.catnip.math.BlockFace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Contraption.class)
public interface ContraptionAccessor {
	@Accessor("stabilizedSubContraptions") Map<UUID, BlockFace> differentialcreate$stabilizedChildren();
}

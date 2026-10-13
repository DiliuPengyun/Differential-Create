package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.trains.LegacyTrainConfig;
import com.simibubi.create.infrastructure.config.CTrains;
import net.createmod.catnip.config.ConfigBase;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CTrains.class)
public abstract class CTrainsMixin extends ConfigBase {
    @Shadow @Final @Mutable public ConfigFloat manualTrainSpeedModifier;
    @Shadow @Final @Mutable public ConfigFloat trainTopSpeed, trainTurningTopSpeed, trainAcceleration;
    @Shadow @Final @Mutable public ConfigFloat poweredTrainTopSpeed, poweredTrainTurningTopSpeed, poweredTrainAcceleration;
    @Shadow @Final public ConfigGroup trainStats, poweredTrainStats;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void differentialcreate$removeLimits(CallbackInfo ci) {
        allValues.remove(trainStats); allValues.remove(poweredTrainStats);
        allValues.remove(manualTrainSpeedModifier); allValues.remove(trainTopSpeed); allValues.remove(trainTurningTopSpeed); allValues.remove(trainAcceleration);
        allValues.remove(poweredTrainTopSpeed); allValues.remove(poweredTrainTurningTopSpeed); allValues.remove(poweredTrainAcceleration);
        manualTrainSpeedModifier = differentialcreate$constant("manualTrainSpeedModifier", 1);
        trainTopSpeed = differentialcreate$constant("trainTopSpeed", 28); trainTurningTopSpeed = differentialcreate$constant("trainTurningTopSpeed", 14);
        trainAcceleration = differentialcreate$constant("trainAcceleration", 3);
        poweredTrainTopSpeed = differentialcreate$constant("poweredTrainTopSpeed", 40); poweredTrainTurningTopSpeed = differentialcreate$constant("poweredTrainTurningTopSpeed", 20);
        poweredTrainAcceleration = differentialcreate$constant("poweredTrainAcceleration", 3);
    }
    @Unique private ConfigFloat differentialcreate$constant(String name, float value) {
        var entry = new LegacyTrainConfig(this, name, value); allValues.remove(entry); return entry;
    }
}

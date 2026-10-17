package com.diliupengyun.differential_create.mixin;

import java.util.List;
import com.diliupengyun.differential_create.kinetics.LoadGaugeBehaviour;
import com.diliupengyun.differential_create.kinetics.LoadGaugeScale;
import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.KineticStressDisplaySource;
import com.simibubi.create.content.redstone.displayLink.source.PercentOrProgressBarDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Retain the source ID and labels; replace nominal stress modes with physical load. */
@Mixin(KineticStressDisplaySource.class)
public abstract class KineticStressDisplaySourceMixin extends PercentOrProgressBarDisplaySource {
    @Inject(method = "getProgress", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$progress(DisplayLinkContext context, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(context.getSourceBlockEntity() instanceof StressGaugeBlockEntity gauge
            ? LoadGaugeScale.position(LoadGaugeBehaviour.reading(gauge)) : null);
    }

    @Inject(method = "formatNumeric", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$percent(DisplayLinkContext context, Float ignored, CallbackInfoReturnable<MutableComponent> cir) {
        cir.setReturnValue(context.getSourceBlockEntity() instanceof StressGaugeBlockEntity gauge
            ? LoadGaugeBehaviour.text(LoadGaugeBehaviour.reading(gauge)).copy() : EMPTY_LINE);
    }

    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        if (context.getSourceBlockEntity() instanceof StressGaugeBlockEntity gauge
            && !LoadGaugeScale.available(LoadGaugeBehaviour.reading(gauge)))
            return LoadGaugeBehaviour.text(LoadGaugeBehaviour.reading(gauge)).copy();
        return super.provideLine(context, stats);
    }

    @Override
    protected String getFlapDisplayLayoutName(DisplayLinkContext context) {
        return differentialcreate$bar(context) ? "DCLoadProgress" : "DCLoadText";
    }

    @Override
    protected FlapDisplaySection createSectionForValue(DisplayLinkContext context, int size) {
        // A dedicated flap cycle carries digits, infinity and translated status text.
        return differentialcreate$bar(context)
            ? new FlapDisplaySection(size * FlapDisplaySection.MONOSPACE, "pixel", false, false).wideFlaps()
            : new FlapDisplaySection(size * FlapDisplaySection.MONOSPACE, "differentialcreate_load", false, false);
    }

    private boolean differentialcreate$bar(DisplayLinkContext context) {
        return context.sourceConfig().getInt("Mode") == 0
            && context.getSourceBlockEntity() instanceof StressGaugeBlockEntity gauge
            && LoadGaugeScale.available(LoadGaugeBehaviour.reading(gauge));
    }

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "initConfigurationWidgets", at = @At("HEAD"), cancellable = true)
    private void differentialcreate$options(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean first, CallbackInfo ci) {
        if (first) super.initConfigurationWidgets(context, builder, true);
        else builder.addSelectionScrollInput(0, 120, (input, label) -> input
            .forOptions(List.of(Component.translatable("gui.differentialcreate.gauge.load_bar"),
                Component.translatable("gui.differentialcreate.gauge.load_percent")))
            .titled(Component.translatable("gui.differentialcreate.gauge.load_display")), "Mode");
        ci.cancel();
    }
}

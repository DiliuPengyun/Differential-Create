package com.diliupengyun.differential_create.mixin;

import com.diliupengyun.differential_create.client.ClientTrainKinetics;
import com.diliupengyun.differential_create.client.TrainControlsClient;
import com.diliupengyun.differential_create.trains.GearLeverBlock;
import com.diliupengyun.differential_create.trains.TrainMechanics;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.TrainHUD;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TrainHUD.class)
public abstract class TrainHUDPhysicsMixin {
    @Shadow private static Double editedThrottle;
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$instrumentPanel(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        ci.cancel(); var mc = Minecraft.getInstance();
        if (mc.options.hideGui || !(ControlsHandler.getContraption() instanceof CarriageContraptionEntity entity) || entity.getCarriage() == null) return;
        var train = entity.getCarriage().train; var s = ClientTrainKinetics.state(train.id);
        int x = graphics.guiWidth() / 2, y = graphics.guiHeight() - 43;
        graphics.fill(x - 135, y - 8, x + 135, y + 36, 0xD018211B);
        graphics.drawCenteredString(mc.font, Component.translatable("gui.differentialcreate.train.speed", String.format(java.util.Locale.ROOT, "%.1f", Math.abs(train.speed) / TrainMechanics.TICK_SECONDS * 3.6)), x, y, 0xFFFFFF);
        String gear = s == null ? "?" : GearLeverBlock.label(s.gear());
        graphics.drawString(mc.font, Component.translatable("gui.differentialcreate.train.gear", gear), x - 128, y, 0xFBDC7D);
        graphics.drawString(mc.font, Component.literal(String.format(java.util.Locale.ROOT, "%.0f RPM", s == null ? 0 : s.engineRpm())), x + 55, y, 0xFBDC7D);
        double valve = editedThrottle == null ? train.throttle : editedThrottle;
        graphics.fill(x - 128, y + 15, x - 28, y + 21, 0xFF384840);
        graphics.fill(x - 128, y + 15, x - 128 + (int) (100 * Math.clamp(valve, 0, 1)), y + 21, 0xFF49B97E);
        graphics.drawString(mc.font, Component.translatable("gui.differentialcreate.train.valve", Math.round(valve * 100)), x - 22, y + 13, 0xFFFFFF);
        if (s != null && !s.failure().equals("ready")) graphics.drawCenteredString(mc.font, Component.translatable("gui.differentialcreate.train." + s.failure()), x, y - 22, 0xFFCA75);
        else if (TrainHUD.currentPrompt != null) graphics.drawCenteredString(mc.font, TrainHUD.currentPrompt, x, y - 22, 0xFBDC7D);
        if (s != null && s.lever()) graphics.drawString(mc.font, Component.translatable("gui.differentialcreate.train.shift_keys", TrainControlsClient.DOWN.getTranslatedKeyMessage(), TrainControlsClient.UP.getTranslatedKeyMessage()), x + 55, y + 13, 0xAAAAAA);
        graphics.drawCenteredString(mc.font, Component.translatable("gui.differentialcreate.train.drive_keys",
            mc.options.keyUp.getTranslatedKeyMessage(), mc.options.keyDown.getTranslatedKeyMessage()), x, y + 26, 0xAAAAAA);
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private static void differentialcreate$closedValve(double amount, CallbackInfoReturnable<Boolean> cir) {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity entity) || entity.getCarriage() == null) return;
        double current = editedThrottle == null ? entity.getCarriage().train.throttle : editedThrottle;
        editedThrottle = Math.clamp(current + Math.signum(amount) / 18, 0, 1); cir.setReturnValue(true);
    }
}

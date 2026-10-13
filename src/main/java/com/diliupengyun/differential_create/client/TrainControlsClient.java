package com.diliupengyun.differential_create.client;

import com.diliupengyun.differential_create.trains.TrainGearPayload;
import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class TrainControlsClient {
    public static final KeyMapping UP = new KeyMapping("key.differentialcreate.gear_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.differentialcreate");
    public static final KeyMapping DOWN = new KeyMapping("key.differentialcreate.gear_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.differentialcreate");
    private TrainControlsClient() {}
    public static void tick() { while (UP.consumeClick()) shift(1); while (DOWN.consumeClick()) shift(-1); }
    private static void shift(int step) {
        if (Minecraft.getInstance().screen != null || !(ControlsHandler.getContraption() instanceof CarriageContraptionEntity entity) || entity.getCarriage() == null) return;
        var train = entity.getCarriage().train; var state = ClientTrainKinetics.state(train.id);
        if (state != null && state.lever()) PacketDistributor.sendToServer(new TrainGearPayload(train.id, step));
    }
    public static void input(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (!event.isUseItem() || !Screen.hasControlDown() || mc.level == null || mc.player == null || mc.screen != null
            || !(mc.hitResult instanceof BlockHitResult hit) || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof SpeedControllerBlockEntity controller)) return;
        event.setCanceled(true); event.setSwingHand(false); mc.setScreen(new RatioPrecisionScreen(hit.getBlockPos(), controller.targetSpeed.getValue()));
    }
}

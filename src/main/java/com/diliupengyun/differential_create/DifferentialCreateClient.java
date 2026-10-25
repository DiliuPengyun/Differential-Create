package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = DifferentialCreate.MODID, dist = Dist.CLIENT)
public class DifferentialCreateClient {
    public DifferentialCreateClient(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) -> {
            net.createmod.ponder.foundation.PonderIndex.addPlugin(
                new com.diliupengyun.differential_create.client.ponder.DifferentialPonderPlugin());
            event.enqueueWork(com.diliupengyun.differential_create.client.KineticItemTooltip::register);
        });
        com.diliupengyun.differential_create.client.SteamBogeyModels.init();
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) ->
            event.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.HOTBAR,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("differentialcreate", "steam_hose"),
                com.diliupengyun.differential_create.client.SteamHosePreview::overlay));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) -> {
            event.register(com.diliupengyun.differential_create.client.TrainControlsClient.UP);
            event.register(com.diliupengyun.differential_create.client.TrainControlsClient.DOWN);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.diliupengyun.differential_create.client.TrainControlsClient.tick());
        NeoForge.EVENT_BUS.addListener(com.diliupengyun.differential_create.client.TrainControlsClient::input);
        NeoForge.EVENT_BUS.addListener(com.diliupengyun.differential_create.client.SteamHoseRenderer::render);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.diliupengyun.differential_create.client.SteamHosePreview.tick());
        NeoForge.EVENT_BUS.addListener(com.diliupengyun.differential_create.client.SteamHosePreview::suppressGoggles);
        // Advances the client-side network speed extrapolation every tick
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientNetworkSpeeds.onClientTick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.diliupengyun.differential_create.client.PortableDockingPreview.tick());
    }
}

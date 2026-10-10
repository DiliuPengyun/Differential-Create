package com.diliupengyun.differential_create;

import com.diliupengyun.differential_create.client.ClientNetworkSpeeds;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = DifferentialCreate.MODID, dist = Dist.CLIENT)
public class DifferentialCreateClient {
    public DifferentialCreateClient() {
        // Advances the client-side network speed extrapolation every tick
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientNetworkSpeeds.onClientTick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> com.diliupengyun.differential_create.client.PortableDockingPreview.tick());
    }
}

package com.diliupengyun.differential_create;

import org.slf4j.Logger;

import com.diliupengyun.differential_create.content.ModContent;
import com.diliupengyun.differential_create.content.ModCreativeModeTabs;
import com.diliupengyun.differential_create.kinetics.NetworkSpeedTicker;
import com.diliupengyun.differential_create.kinetics.NetworkStatePayload;
import com.diliupengyun.differential_create.kinetics.NetworkStructurePayload;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(DifferentialCreate.MODID)
public class DifferentialCreate {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "differentialcreate";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MODID)
        .defaultCreativeTab(ModCreativeModeTabs.MAIN.getKey());

    public static CreateRegistrate registrate() {
        return REGISTRATE;
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public DifferentialCreate(IEventBus modEventBus, ModContainer modContainer) {
        ModCreativeModeTabs.register(modEventBus);
        // Bind the bus before content builders attach client renderer/visualizer callbacks.
        REGISTRATE.registerEventListeners(modEventBus);
        ModContent.register();
        // Drives the continuous network speeds on the server tick
        NeoForge.EVENT_BUS.register(NetworkSpeedTicker.class);
        // Clientbound per-network speed sync (structure + state packets)
        modEventBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> {
            // Bearing poses and rotor states describe the same physical instant.
            event.registrar("12")
                .playToServer(com.diliupengyun.differential_create.kinetics.RatioSettingPayload.TYPE,
                    com.diliupengyun.differential_create.kinetics.RatioSettingPayload.STREAM_CODEC,
                    com.diliupengyun.differential_create.kinetics.RatioSettingPayload::handle)
                .playToClient(com.diliupengyun.differential_create.trains.TrainStatePayload.TYPE,
                    com.diliupengyun.differential_create.trains.TrainStatePayload.STREAM_CODEC,
                    com.diliupengyun.differential_create.trains.TrainStatePayload::handle)
                .playToServer(com.diliupengyun.differential_create.trains.TrainGearPayload.TYPE,
                    com.diliupengyun.differential_create.trains.TrainGearPayload.STREAM_CODEC,
                    com.diliupengyun.differential_create.trains.TrainGearPayload::handle)
                .playToClient(com.diliupengyun.differential_create.kinetics.MovingHandPayload.TYPE,
                    com.diliupengyun.differential_create.kinetics.MovingHandPayload.STREAM_CODEC,
                    com.diliupengyun.differential_create.kinetics.MovingHandPayload::handle)
                .playToClient(com.diliupengyun.differential_create.kinetics.RotorStatePayload.TYPE,
                    com.diliupengyun.differential_create.kinetics.RotorStatePayload.STREAM_CODEC,
                    com.diliupengyun.differential_create.kinetics.RotorStatePayload::handle)
                .playToClient(NetworkStructurePayload.TYPE, NetworkStructurePayload.STREAM_CODEC,
                    NetworkStructurePayload::handle)
                .playToClient(NetworkStatePayload.TYPE, NetworkStatePayload.STREAM_CODEC,
                    NetworkStatePayload::handle);
        });
    }
}

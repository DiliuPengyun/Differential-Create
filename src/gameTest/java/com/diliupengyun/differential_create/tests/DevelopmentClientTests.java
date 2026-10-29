package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.DifferentialCreate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Test entry points are discovered only when the development source set is loaded. */
@EventBusSubscriber(modid = DifferentialCreate.MODID, value = Dist.CLIENT)
public final class DevelopmentClientTests {
    private DevelopmentClientTests() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (Boolean.getBoolean("differentialcreate.structureClientAudit")) {
                NeoForge.EVENT_BUS.addListener(StructureClientAudit::frame);
                NeoForge.EVENT_BUS.addListener(StructureClientAudit::tick);
            }
            if (Boolean.getBoolean("differentialcreate.trainClientSmoke")) {
                NeoForge.EVENT_BUS.addListener(TrainClientSmokeTests::frame);
                NeoForge.EVENT_BUS.addListener(TrainClientSmokeTests::render);
            }
            if (Boolean.getBoolean("differentialcreate.loadGaugeClientSmoke"))
                NeoForge.EVENT_BUS.addListener(LoadGaugeClientSmokeTests::frame);
            if (Boolean.getBoolean("differentialcreate.ejectorClientSmoke"))
                NeoForge.EVENT_BUS.addListener(EjectorClientSmokeTests::frame);
            if (Boolean.getBoolean("differentialcreate.logisticsClientSmoke"))
                NeoForge.EVENT_BUS.addListener(Create6ClientSmokeTests::frame);
            if (Boolean.getBoolean("differentialcreate.beltClientSmoke"))
                NeoForge.EVENT_BUS.addListener(BeltClientSmokeTests::frame);
            if (Boolean.getBoolean("differentialcreate.ponderClientSmoke"))
                NeoForge.EVENT_BUS.addListener(PonderClientSmokeTests::frame);
        });
    }
}

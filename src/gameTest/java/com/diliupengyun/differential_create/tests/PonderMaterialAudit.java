package com.diliupengyun.differential_create.tests;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;

/** Verify material provenance and contact, not just whether the storyboard plays to its end. */
final class PonderMaterialAudit {
    private final Set<TransportedItemStack> inputs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TransportedItemStack> products = Collections.newSetFromMap(new IdentityHashMap<>());
    private int beltInputs, depotInputs;
    private float lastHandOffset;
    private boolean fallingOre, enteredGap, acceptedOre;

    /** Normal playback refreshes the BE ticker cache while rendering newly shown sections. */
    static void renderSections(PonderScene scene) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var graphics = new net.minecraft.client.gui.GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        var sink = new VertexSink();
        scene.forEachVisible(net.createmod.ponder.foundation.element.WorldSectionElementImpl.class,
            section -> section.renderFirst(scene.getWorld(), ignored -> sink, graphics, 1, 1));
    }

    private static final class VertexSink implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override public VertexSink addVertex(float x, float y, float z) {
            require(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "Non-finite processing model vertex");
            return this;
        }
        @Override public VertexSink setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexSink setUv(float u, float v) { return this; }
        @Override public VertexSink setUv1(int u, int v) { return this; }
        @Override public VertexSink setUv2(int u, int v) { return this; }
        @Override public VertexSink setNormal(float x, float y, float z) { return this; }
    }

    void sample(PonderScene scene) {
        for (var entity : scene.getWorld().getEntityList()) if (entity instanceof ItemEntity item) {
            require(Double.isFinite(item.getX()) && Double.isFinite(item.getY()) && Double.isFinite(item.getZ()),
                "Non-finite item position in " + scene.getId());
            if (scene.getId().getPath().equals("crusher_work") && item.getItem().is(Items.GOLD_ORE)) {
                fallingOre |= item.getY() > 3;
                enteredGap |= item.getY() < 2.9;
            }
        }
        if (scene.getId().getPath().equals("crusher_work"))
            for (var be : scene.getWorld().getBlockEntities())
                if (be instanceof CrushingWheelControllerBlockEntity crusher)
                    acceptedOre |= crusher.inventory.getStackInSlot(0).is(Items.GOLD_ORE);
        if (!scene.getId().toString().equals("create:mechanical_press")
            && !scene.getId().toString().equals("create:deployer_processing")) return;
        var press = java.util.stream.StreamSupport.stream(scene.getWorld().getBlockEntities().spliterator(), false)
            .filter(MechanicalPressBlockEntity.class::isInstance).map(MechanicalPressBlockEntity.class::cast)
            .findFirst().orElse(null);
        for (var be : scene.getWorld().getBlockEntities()) {
            if (be instanceof BeltBlockEntity belt && belt.isController())
                for (var item : belt.getInventory().getTransportedItems()) observe(scene, item, press, true);
            if (be instanceof DepotBlockEntity depot) {
                var handler = depot.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
                handler.handleProcessingOnAllItems(item -> {
                    observe(scene, item, press, false);
                    return TransportedResult.doNothing();
                });
            }
        }
        if (scene.getWorld().getBlockEntity(new net.minecraft.core.BlockPos(2, 3, 2)) instanceof DeployerBlockEntity deployer)
            lastHandOffset = deployer.getHandOffset(1);
    }

    private void observe(PonderScene scene, TransportedItemStack item, MechanicalPressBlockEntity press, boolean belt) {
        require(Float.isFinite(item.beltPosition), "Non-finite transported position in " + scene.getId());
        boolean pressingScene = scene.getId().getPath().equals("mechanical_press");
        var input = pressingScene ? Items.COPPER_INGOT : AllItems.ROSE_QUARTZ.get();
        var product = pressingScene ? AllItems.COPPER_SHEET.get() : AllItems.POLISHED_ROSE_QUARTZ.get();
        if (item.stack.is(input) && inputs.add(item)) {
            require(item.stack.getCount() == 1, "Native processing demonstration must provide single workpieces");
            if (belt) beltInputs++; else depotInputs++;
        }
        if (item.stack.is(product) && products.add(item)) {
            // Create's recipe callback replaces a transported stack with new result objects.
            // Provenance is a conserved count, not Java object identity across that conversion.
            require(products.size() <= inputs.size(), "Product has no preceding input in " + scene.getId() + " at tick " + scene.getCurrentTime());
            if (!pressingScene) {
                var deployer = (DeployerBlockEntity) scene.getWorld().getBlockEntity(new net.minecraft.core.BlockPos(2, 3, 2));
                require(Math.max(lastHandOffset, deployer.getHandOffset(1)) >= .99f,
                    "Polished quartz precedes hand contact at tick " + scene.getCurrentTime() + ": " + deployer.getHandOffset(1));
                return;
            }
            var pressing = press.getPressingBehaviour();
            require(pressing.running && pressing.runningTicks >= PressingBehaviour.CYCLE / 2
                && pressing.runningTicks < PressingBehaviour.CYCLE * 2 / 3,
                "Sheet precedes press contact at tick " + scene.getCurrentTime() + ": " + pressing.runningTicks);
        }
    }

    void verify(PonderScene scene) {
        if (scene.getId().toString().equals("create:mechanical_press")
            || scene.getId().toString().equals("create:deployer_processing"))
            require(depotInputs == 1 && beltInputs == 2 && products.size() == 3,
                "Native processing must transform one depot and two belt inputs into exactly three products in " + scene.getId() + ": "
                    + depotInputs + "/" + beltInputs + "/" + products.size());
        if (scene.getId().getPath().equals("crusher_work"))
            require(fallingOre && enteredGap && acceptedOre,
                "Crusher must draw its falling ore into the actual processing inventory: "
                    + fallingOre + "/" + enteredGap + "/" + acceptedOre);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

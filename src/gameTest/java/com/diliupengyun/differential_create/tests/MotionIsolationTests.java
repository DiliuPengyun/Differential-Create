package com.diliupengyun.differential_create.tests;

import com.diliupengyun.differential_create.kinetics.ContraptionMotion;
import com.diliupengyun.differential_create.kinetics.LinearActuatorContact;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Loading/removing a replica must not touch the integrated server's pending motion. */
final class MotionIsolationTests {
    static void run(GameTestHelper h) {
        var entity = new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(), h.getLevel());
        var tag = new CompoundTag(); var motion = new CompoundTag(); var contact = new CompoundTag();
        motion.putDouble("Y", -.125); tag.put("DCOutputMotion", motion);
        contact.putInt("Direction", 0); contact.putDouble("Y", 5); tag.put("DCLinearContact", contact);
        ContraptionMotion.load(entity, tag);
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                ContraptionMotion.load(entity, new CompoundTag());
                ContraptionMotion.forget(entity);
                ContraptionMotion.clear();
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            h.assertTrue(new Vec3(0, -.125, 0).equals(ContraptionMotion.linear(entity)),
                "client-side lifecycle must not erase the server's next accepted displacement");
            h.assertTrue(LinearActuatorContact.stop(entity, new Vec3(0, -1, 0)) == 1,
                "client-side lifecycle must not erase the server's collision support");
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { ContraptionMotion.forget(entity); }
        h.succeed();
    }
}

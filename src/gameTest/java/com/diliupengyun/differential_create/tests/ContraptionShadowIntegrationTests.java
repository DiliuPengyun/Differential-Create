package com.diliupengyun.differential_create.tests;

import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Disposable native controller fixtures for isolated geometry changes and readback. */
@GameTestHolder("differentialcreate_motion")
@PrefixGameTestTemplate(false)
public final class ContraptionShadowIntegrationTests {
    private ContraptionShadowIntegrationTests() {}
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var cases=new ArrayList<TestFunction>();
        for(String mode:List.of("actuators_clock","gantry","bearing")) {
            String name="contraption_shadow_"+mode;
            if(!name.matches(System.getProperty("differentialcreate.motion.select",".*")))continue;
            cases.add(new TestFunction(name,name,"differentialcreate_motion:kineticphysicstests.physics_test",100,0,true,h->{
                KineticPhysicsTests.forceImpactChunks(h);
                switch(mode) {
                    case "actuators_clock" -> ContraptionPhysicsTests.mass(h);
                    case "gantry" -> ContraptionPhysicsTests.gantry(h);
                    default -> ContraptionPhysicsTests.rotationalShadow(h);
                }
            }));
        }
        return cases;
    }
}

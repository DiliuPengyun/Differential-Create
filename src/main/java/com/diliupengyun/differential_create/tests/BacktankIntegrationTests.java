package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Both backtank materials, using native entities and a disposable server world. */
@GameTestHolder("differentialcreate_backtank")
@PrefixGameTestTemplate(false)
public final class BacktankIntegrationTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var tests = new ArrayList<TestFunction>();
        for (boolean netherite : new boolean[]{false, true}) {
            String material = netherite ? "netherite" : "copper";
            for (double speed : new double[]{.01, 128, -128, 8192})
                tests.add(test(material + "_motion_" + Double.toString(speed).replace('.', '_'),
                    h -> BacktankTests.motion(h, netherite, speed)));
            for (int direction : new int[]{-1, 1})
                tests.add(test(material + "_full_" + direction, h -> BacktankTests.full(h, netherite, direction)));
            tests.add(test(material + "_states", h -> BacktankTests.states(h, netherite)));
            tests.add(test(material + "_persistence", h -> BacktankTests.persistence(h, netherite)));
        }
        return tests;
    }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(name, name, "differentialcreate_backtank:kineticphysicstests.physics_test", 100, 0, true, helper -> {
            KineticPhysicsTests.forceImpactChunks(helper);
            body.accept(helper);
        });
    }
}

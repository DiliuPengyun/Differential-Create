package com.diliupengyun.differential_create.tests;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused, disposable-world entry point for instrument changes. */
@GameTestHolder("differentialcreate_gauge")
@PrefixGameTestTemplate(false)
public final class LoadGaugeIntegrationTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        return List.of(test("load_readout", LoadFactorTests::run), test("load_gauge", LoadGaugeTests::run),
            test("load_display", LoadGaugeTests::displayLink),
            test("speed_gauge", SpeedGaugeTests::run));
    }
    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction(name, name, "differentialcreate_gauge:kineticphysicstests.physics_test", 180, 0, true, helper -> {
            KineticPhysicsTests.forceImpactChunks(helper);
            body.accept(helper);
        });
    }
}

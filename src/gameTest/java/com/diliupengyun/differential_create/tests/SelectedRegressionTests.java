package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Repeat a declared regression in fresh structures to investigate ordering-sensitive failures. */
@GameTestHolder("differentialcreate_regression")
@PrefixGameTestTemplate(false)
public final class SelectedRegressionTests {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        String selected = System.getProperty("differentialcreate.regression.select", "(?!)");
        int repeats = Math.clamp(Integer.getInteger("differentialcreate.regression.repeat", 1), 1, 10);
        for (var owner : java.util.List.of(KineticPhysicsTests.class, FluidTopologyIntegrationTests.class))
            for (var method : owner.getDeclaredMethods()) {
                var test = method.getAnnotation(GameTest.class);
                if (test == null || !method.getName().matches(selected)) continue;
                for (int i = 0; i < repeats; i++) {
                    String name = method.getName().toLowerCase(java.util.Locale.ROOT) + "_" + i;
                    result.add(new TestFunction(name, name, "differentialcreate_regression:kineticphysicstests.physics_test", test.timeoutTicks(), 0, true, helper -> {
                        try { method.invoke(null, helper); }
                        catch (ReflectiveOperationException failure) {
                            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation ? invocation.getCause() : failure;
                            if (cause instanceof RuntimeException runtime) throw runtime;
                            if (cause instanceof Error error) throw error;
                            throw new IllegalStateException("Selected regression failed", cause);
                        }
                    }));
                }
            }
        return result;
    }
}

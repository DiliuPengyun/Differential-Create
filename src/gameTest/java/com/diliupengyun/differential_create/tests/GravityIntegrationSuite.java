package com.diliupengyun.differential_create.tests;

import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Selectable real-controller regressions in a disposable world. */
@GameTestHolder("differentialcreate_gravity")
@PrefixGameTestTemplate(false)
public final class GravityIntegrationSuite {
    @GameTestGenerator public static Collection<TestFunction> cases() {
        var result = new ArrayList<TestFunction>();
        String selected = System.getProperty("differentialcreate.gravity.select", ".*");
        result.addAll(GravityAccelerationMeasurements.cases(selected));
        for (var method : KineticPhysicsTests.class.getDeclaredMethods()) {
            var test = method.getAnnotation(GameTest.class);
            if (test == null || java.util.Arrays.stream(selected.split(",")).noneMatch(method.getName()::matches)) continue;
            String name = "gravity_regression_" + method.getName().toLowerCase(java.util.Locale.ROOT);
            result.add(new TestFunction(name, name, "differentialcreate_gravity:kineticphysicstests.physics_test", test.timeoutTicks(), 0, true, helper -> {
                try { method.invoke(null, helper); }
                catch (ReflectiveOperationException failure) {
                    Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation ? invocation.getCause() : failure;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("Controller gravity regression failed", cause);
                }
            }));
        }
        return result;
    }
}
